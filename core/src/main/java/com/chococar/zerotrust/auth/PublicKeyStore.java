package com.chococar.zerotrust.auth;

import com.chococar.zerotrust.platform.KeyRepository;
import com.chococar.zerotrust.platform.StoredKey;
import com.chococar.zerotrust.util.Hex;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公鑰存取（計劃 3.6 多金鑰）。由 {@link KeyRepository} 持久化，核心僅存<b>公鑰</b>
 * （外洩無法偽造簽名，計劃 6.2）。
 *
 * <ul>
 *   <li>每個管理員可註冊多把公鑰，各帶 label、來源與 last-used 時間。</li>
 *   <li>新增時以 {@link Ed25519Verifier#parsePublicKey(String)} 驗證金鑰合法性，
 *       拒絕 RSA / EC / DSA / 畸形（計劃 3.5 金鑰類型混淆防護）。</li>
 *   <li>同 label 視為同一裝置，新增即取代。</li>
 *   <li>{@code verifyAgainstAnyKey}：任一把成功即通過，並更新該把 last-used 後落盤。</li>
 * </ul>
 *
 * <p>狀態以 {@link ConcurrentHashMap} 保存；每把金鑰的異動以 per-uuid 同步序列化，
 * 避免並行寫入造成清單不一致。
 */
public final class PublicKeyStore {

    private final KeyRepository repo;
    private final Ed25519Verifier verifier;
    private final Clock clock;

    /** uuid → 該帳號名下的金鑰清單（CopyOnWrite 風格：每次異動建立新清單）。 */
    private final Map<UUID, List<StoredKey>> keysByUuid = new ConcurrentHashMap<>();

    public PublicKeyStore(KeyRepository repo, Ed25519Verifier verifier, Clock clock) {
        this.repo = repo;
        this.verifier = verifier;
        this.clock = clock;
        // 啟動載入既有金鑰（原始 Base64 保留；解析 / 驗證於使用時進行）。
        Map<UUID, List<StoredKey>> loaded = repo.loadAll();
        if (loaded != null) {
            for (Map.Entry<UUID, List<StoredKey>> e : loaded.entrySet()) {
                if (e.getValue() != null) {
                    keysByUuid.put(e.getKey(), new ArrayList<>(e.getValue()));
                }
            }
        }
    }

    /**
     * 新增（或以同 label 取代）一把公鑰並落盤。
     *
     * @throws IllegalArgumentException 公鑰非合法 Ed25519（呼叫端轉為 {@code CommandResult.fail}）
     */
    public synchronized void addKey(UUID uuid, String base64PublicKey, String source, String label) {
        // 驗證金鑰合法（畸形 / 非 Ed25519 會丟 IllegalArgumentException）。
        verifier.parsePublicKey(base64PublicKey);

        String effLabel = (label == null || label.isBlank()) ? "default" : label;
        String effSource = (source == null || source.isBlank()) ? "generated" : source;

        List<StoredKey> current = keysByUuid.getOrDefault(uuid, Collections.emptyList());
        List<StoredKey> next = new ArrayList<>(current.size() + 1);
        for (StoredKey k : current) {
            if (!k.label().equals(effLabel)) {
                next.add(k);
            }
        }
        next.add(new StoredKey(effLabel, base64PublicKey.trim(), effSource, null));
        keysByUuid.put(uuid, next);
        repo.save(uuid, Collections.unmodifiableList(new ArrayList<>(next)));
    }

    public boolean hasKeys(UUID uuid) {
        List<StoredKey> list = keysByUuid.get(uuid);
        return list != null && !list.isEmpty();
    }

    /** 回傳該帳號名下金鑰清單的唯讀快照（無則空清單）。 */
    public List<StoredKey> getStoredKeys(UUID uuid) {
        List<StoredKey> list = keysByUuid.get(uuid);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /** 移除指定 label 的金鑰並落盤。 */
    public synchronized boolean removeKey(UUID uuid, String label) {
        List<StoredKey> current = keysByUuid.get(uuid);
        if (current == null || current.isEmpty() || label == null) {
            return false;
        }
        List<StoredKey> next = new ArrayList<>(current.size());
        boolean removed = false;
        for (StoredKey k : current) {
            if (k.label().equals(label)) {
                removed = true;
            } else {
                next.add(k);
            }
        }
        if (!removed) {
            return false;
        }
        if (next.isEmpty()) {
            keysByUuid.remove(uuid);
        } else {
            keysByUuid.put(uuid, next);
        }
        repo.save(uuid, Collections.unmodifiableList(new ArrayList<>(next)));
        return true;
    }

    /** 移除該帳號全部金鑰並落盤（撤銷全部，計劃 6.5）。 */
    public synchronized void removeAll(UUID uuid) {
        keysByUuid.remove(uuid);
        repo.save(uuid, Collections.emptyList());
    }

    /**
     * 以該帳號名下任一把公鑰驗證簽名；首把成功即更新其 last-used 並落盤。
     *
     * @return 成功時回傳該把公鑰的指紋（hex SHA-256）；全部失敗回傳空。
     */
    public Optional<String> verifyAgainstAnyKey(UUID uuid, String domain, byte[] nonce, byte[] signature) {
        List<StoredKey> list = keysByUuid.get(uuid);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        for (StoredKey stored : list) {
            final PublicKey key;
            try {
                key = verifier.parsePublicKey(stored.publicKeyBase64());
            } catch (RuntimeException ex) {
                // 持久化內容若損毀（非 Ed25519），跳過該把，繼續嘗試其他金鑰（fail-closed）。
                continue;
            }
            if (verifier.verify(domain, nonce, signature, key)) {
                updateLastUsed(uuid, stored.label());
                return Optional.of(fingerprint(key));
            }
        }
        return Optional.empty();
    }

    /** 更新指定 label 金鑰的 last-used 時間並落盤。 */
    private synchronized void updateLastUsed(UUID uuid, String label) {
        List<StoredKey> current = keysByUuid.get(uuid);
        if (current == null) {
            return;
        }
        Instant now = clock.instant();
        List<StoredKey> next = new ArrayList<>(current.size());
        boolean changed = false;
        for (StoredKey k : current) {
            if (k.label().equals(label)) {
                next.add(k.withLastUsed(now));
                changed = true;
            } else {
                next.add(k);
            }
        }
        if (changed) {
            keysByUuid.put(uuid, next);
            repo.save(uuid, Collections.unmodifiableList(new ArrayList<>(next)));
        }
    }

    /** 公鑰指紋：其 X.509 編碼的 SHA-256，小寫十六進位。 */
    public String fingerprint(PublicKey key) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return Hex.encode(sha256.digest(key.getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package com.chococar.zerotrust.paper;

import com.chococar.zerotrust.platform.KeyRepository;
import com.chococar.zerotrust.platform.StoredKey;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * {@link KeyRepository} 的 YAML 實作：以 Bukkit {@link FileConfiguration}（{@code config.yml}）
 * 的 {@code admins[]} 區段作為公鑰來源（計劃 3.6 / 6.2）。
 *
 * <p><b>只持久化公鑰</b>（{@link StoredKey#publicKeyBase64()}）。寫回時僅更新目標管理員的
 * {@code keys[]}，保留其他管理員與其 uuid。
 *
 * <p>執行緒安全：所有對 {@link FileConfiguration} 的讀寫都在此物件的鎖內進行。寫檔（{@code saveConfig}）
 * 為 IO，引擎可能從任意執行緒呼叫 {@link #save}；Bukkit 的 {@code saveConfig} 不要求主執行緒，
 * 故此處同步寫檔即可。
 */
final class YamlKeyRepository implements KeyRepository {

    private final Plugin plugin;
    private final Logger log;
    private final Object lock = new Object();

    YamlKeyRepository(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.log = plugin.getLogger();
    }

    @Override
    public Map<UUID, List<StoredKey>> loadAll() {
        synchronized (lock) {
            Map<UUID, List<StoredKey>> result = new LinkedHashMap<>();
            FileConfiguration cfg = plugin.getConfig();
            List<Map<?, ?>> admins = cfg.getMapList("admins");
            if (admins == null) {
                return result;
            }
            for (Map<?, ?> entry : admins) {
                Object rawUuid = entry.get("uuid");
                if (rawUuid == null) {
                    continue;
                }
                String uuidStr = String.valueOf(rawUuid).trim();
                if (uuidStr.isEmpty()) {
                    continue;
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidStr);
                } catch (IllegalArgumentException ex) {
                    // 略過佔位符 / 畸形 UUID（SelfTest 透過 loadAll() 不應因範例設定而 fail）。
                    continue;
                }
                result.put(uuid, parseKeys(entry.get("keys"), uuid));
            }
            return result;
        }
    }

    @Override
    public void save(UUID uuid, List<StoredKey> keys) {
        Objects.requireNonNull(uuid, "uuid");
        List<StoredKey> safeKeys = keys == null ? List.of() : keys;
        synchronized (lock) {
            FileConfiguration cfg = plugin.getConfig();

            // 讀出現有 admins，重建一份可變結構（getMapList 的元素未必可寫）。
            List<Map<?, ?>> existing = cfg.getMapList("admins");
            List<Map<String, Object>> rebuilt = new ArrayList<>();
            boolean found = false;

            if (existing != null) {
                for (Map<?, ?> entry : existing) {
                    Map<String, Object> copy = copyEntry(entry);
                    Object rawUuid = copy.get("uuid");
                    if (rawUuid != null && uuid.toString().equalsIgnoreCase(String.valueOf(rawUuid).trim())) {
                        copy.put("keys", serializeKeys(safeKeys));
                        found = true;
                    }
                    rebuilt.add(copy);
                }
            }

            if (!found) {
                // 此管理員尚未在 config 中（例如剛 enroll 後首次 upload）：新增一筆。
                Map<String, Object> fresh = new LinkedHashMap<>();
                fresh.put("uuid", uuid.toString());
                fresh.put("keys", serializeKeys(safeKeys));
                rebuilt.add(fresh);
            }

            cfg.set("admins", rebuilt);
            try {
                plugin.saveConfig();
            } catch (RuntimeException e) {
                log.severe("ZeroTrust 寫回公鑰至 config.yml 失敗：" + e.getMessage());
            }
        }
    }

    @Override
    public void flush() {
        synchronized (lock) {
            try {
                plugin.saveConfig();
            } catch (RuntimeException e) {
                log.warning("ZeroTrust flush config.yml 失敗：" + e.getMessage());
            }
        }
    }

    // ── 內部 ────────────────────────────────────────────────

    private List<StoredKey> parseKeys(Object rawKeys, UUID uuid) {
        List<StoredKey> out = new ArrayList<>();
        if (!(rawKeys instanceof List<?> list)) {
            return out;
        }
        for (Object k : list) {
            if (!(k instanceof Map<?, ?> km)) {
                continue;
            }
            String label = str(km.get("label"));
            String publicKey = str(km.get("public_key"));
            String source = str(km.get("source"));
            String lastUsedStr = str(km.get("last_used"));

            if (publicKey == null || publicKey.isBlank()) {
                continue;
            }
            if (label == null || label.isBlank()) {
                label = "default";
            }
            if (source == null || source.isBlank()) {
                source = "generated";
            }
            Instant lastUsed = null;
            if (lastUsedStr != null && !lastUsedStr.isBlank()) {
                try {
                    lastUsed = Instant.parse(lastUsedStr.trim());
                } catch (DateTimeParseException ex) {
                    log.warning("管理員 " + uuid + " 金鑰 last_used 格式無效：" + lastUsedStr);
                }
            }
            out.add(new StoredKey(label, publicKey.trim(), source, lastUsed));
        }
        return out;
    }

    private static List<Map<String, Object>> serializeKeys(List<StoredKey> keys) {
        List<Map<String, Object>> out = new ArrayList<>(keys.size());
        for (StoredKey k : keys) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", k.label());
            m.put("public_key", k.publicKeyBase64());
            m.put("source", k.source());
            // ISO-8601；null 寫成空字串以維持結構可讀。
            m.put("last_used", k.lastUsed() == null ? "" : k.lastUsed().toString());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> copyEntry(Map<?, ?> entry) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : entry.entrySet()) {
            copy.put(String.valueOf(e.getKey()), e.getValue());
        }
        return copy;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

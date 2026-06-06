package com.chococar.zerotrust.common;

import com.chococar.zerotrust.platform.KeyRepository;
import com.chococar.zerotrust.platform.StoredKey;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * {@link KeyRepository} 的 YAML 實作（SnakeYAML），供非 Bukkit 平台共用。
 *
 * <p>持久化於<b>專屬</b>檔 {@code keys.yml}（不寫使用者的 {@code config.yml}，避免覆蓋註解）。
 * 只存<b>公鑰</b>（外洩無法偽造簽名，計劃 6.2）。執行緒安全。
 *
 * <pre>
 * keys:
 *   "&lt;uuid&gt;":
 *     - {label: desktop, public_key: "...", source: generated, last_used: "2026-..."}
 * </pre>
 */
public final class YamlKeyRepository implements KeyRepository {

    private final Path keysFile;
    private final Logger log;
    private final Map<UUID, List<StoredKey>> cache = new HashMap<>();

    public YamlKeyRepository(Path keysFile, Logger log) {
        this.keysFile = keysFile;
        this.log = log;
        loadFromDisk();
    }

    @Override
    public synchronized Map<UUID, List<StoredKey>> loadAll() {
        Map<UUID, List<StoredKey>> copy = new HashMap<>();
        for (Map.Entry<UUID, List<StoredKey>> e : cache.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }

    @Override
    public synchronized void save(UUID uuid, List<StoredKey> keys) {
        if (uuid == null) {
            return;
        }
        if (keys == null || keys.isEmpty()) {
            cache.remove(uuid);
        } else {
            cache.put(uuid, new ArrayList<>(keys));
        }
        dumpToDisk();
    }

    // ── 內部 ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (keysFile == null || !Files.exists(keysFile)) {
            return;
        }
        try {
            String text = Files.readString(keysFile, StandardCharsets.UTF_8);
            Object root = new Yaml().load(text);
            if (!(root instanceof Map)) {
                return;
            }
            Object keysObj = ((Map<String, Object>) root).get("keys");
            if (!(keysObj instanceof Map)) {
                return;
            }
            for (Map.Entry<?, ?> e : ((Map<?, ?>) keysObj).entrySet()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(String.valueOf(e.getKey()).trim());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                List<StoredKey> list = new ArrayList<>();
                if (e.getValue() instanceof List<?> entries) {
                    for (Object o : entries) {
                        if (o instanceof Map<?, ?> km) {
                            String label = str(km.get("label"), "default");
                            String pub = str(km.get("public_key"), null);
                            String source = str(km.get("source"), "generated");
                            Instant lastUsed = parseInstant(str(km.get("last_used"), null));
                            if (pub != null && !pub.isBlank()) {
                                list.add(new StoredKey(label, pub, source, lastUsed));
                            }
                        }
                    }
                }
                if (!list.isEmpty()) {
                    cache.put(uuid, list);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("無法讀取金鑰庫：" + keysFile, e);
        } catch (RuntimeException e) {
            if (log != null) log.warning("金鑰庫解析失敗，視為空：" + e.getMessage());
        }
    }

    private void dumpToDisk() {
        Map<String, Object> keysMap = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<StoredKey>> e : cache.entrySet()) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (StoredKey k : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", k.label());
                m.put("public_key", k.publicKeyBase64());
                m.put("source", k.source());
                m.put("last_used", k.lastUsed() == null ? "" : k.lastUsed().toString());
                entries.add(m);
            }
            keysMap.put(e.getKey().toString(), entries);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("keys", keysMap);

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        String yaml = new Yaml(opts).dump(root);
        try {
            if (keysFile.toAbsolutePath().getParent() != null) {
                Files.createDirectories(keysFile.toAbsolutePath().getParent());
            }
            Files.writeString(keysFile, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (log != null) log.warning("金鑰庫寫入失敗：" + e.getMessage());
        }
    }

    private static String str(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

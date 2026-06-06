package com.chococar.zerotrust.common;

import com.chococar.zerotrust.config.ZeroTrustConfig;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 以 SnakeYAML 解析 {@code config.yml}（計劃 7.3）為 {@link ZeroTrustConfig} 與部署中繼資料，
 * 供非 Bukkit 平台（Fabric / Forge / NeoForge）共用。Paper 使用 Bukkit 內建 YAML，不走此類。
 *
 * <p><b>秘密不在此檔</b>：{@code DISCORD_BOT_TOKEN}、{@code IP_HMAC_SECRET} 一律由環境變數讀取。
 */
public final class YamlConfigLoader {

    private YamlConfigLoader() {}

    /** 解析結果：核心設定 + 部署中繼資料。 */
    public static final class Loaded {
        public final ZeroTrustConfig config;
        public final Set<UUID> admins;
        public final String adminPermissionNode;
        public final String discordAdminId;
        public final String discordFallbackChannelId;

        Loaded(ZeroTrustConfig config, Set<UUID> admins, String adminPermissionNode,
               String discordAdminId, String discordFallbackChannelId) {
            this.config = config;
            this.admins = admins;
            this.adminPermissionNode = adminPermissionNode;
            this.discordAdminId = discordAdminId;
            this.discordFallbackChannelId = discordFallbackChannelId;
        }
    }

    /** 預設設定（首次啟動寫出）；佔位符，無任何秘密。 */
    public static final String DEFAULT_YAML =
            "# ZeroTrustAuth 設定。秘密走環境變數（DISCORD_BOT_TOKEN、IP_HMAC_SECRET），切勿寫於此。\n"
            + "admins: []   # 每筆：{ uuid: \"...\", node: \"zerotrust.admin\" }；金鑰由 enroll 後存於 keys.yml\n"
            + "settings:\n"
            + "  session_ttl_hours: 4\n"
            + "  max_attempts: 3\n"
            + "  option_a_timeout_seconds: 10\n"
            + "  option_b_token_ttl_minutes: 5\n"
            + "  enrollment_token_ttl_minutes: 10\n"
            + "  enrollment_max_attempts: 5\n"
            + "  allow_fallback: true\n"
            + "  freeze_packet_limit_per_second: 20\n"
            + "  trusted_device_window_minutes: 15\n"
            + "  strip_vanilla_op: true\n"
            + "  fail_closed: true\n"
            + "security:\n"
            + "  signature_domain: \"MC-ZEROTRUST-AUTH-v1:\"\n"
            + "  startup_self_test: true\n"
            + "permissions:\n"
            + "  admin_node: \"zerotrust.admin\"\n"
            + "discord:\n"
            + "  admin_discord_id: \"\"\n"
            + "  notify_cooldown_seconds: 60\n"
            + "  fallback_channel_id: \"\"\n"
            + "logging:\n"
            + "  format: \"json\"\n"
            + "  rotation: \"daily\"\n"
            + "  retention_days: 90\n";

    /** 載入設定；若檔案不存在則寫出預設並載入之。 */
    public static Loaded load(Path configFile, Logger log) {
        try {
            if (!Files.exists(configFile)) {
                Files.createDirectories(configFile.toAbsolutePath().getParent());
                Files.writeString(configFile, DEFAULT_YAML, StandardCharsets.UTF_8);
                if (log != null) log.info("已寫出預設 config.yml：" + configFile);
            }
            String text = Files.readString(configFile, StandardCharsets.UTF_8);
            return parse(text);
        } catch (IOException e) {
            throw new UncheckedIOException("無法讀取設定：" + configFile, e);
        }
    }

    /** 解析 YAML 文字（供測試直接呼叫）。 */
    @SuppressWarnings("unchecked")
    public static Loaded parse(String yamlText) {
        Object root = new Yaml().load(yamlText);
        Map<String, Object> top = root instanceof Map ? (Map<String, Object>) root : Map.of();

        Map<String, Object> settings = section(top, "settings");
        Map<String, Object> security = section(top, "security");
        Map<String, Object> discord = section(top, "discord");
        Map<String, Object> logging = section(top, "logging");
        Map<String, Object> permissions = section(top, "permissions");

        ZeroTrustConfig config = ZeroTrustConfig.builder()
                .sessionTtl(Duration.ofHours(getLong(settings, "session_ttl_hours", 4)))
                .maxAttempts((int) getLong(settings, "max_attempts", 3))
                .optionATimeout(Duration.ofSeconds(getLong(settings, "option_a_timeout_seconds", 10)))
                .optionBTokenTtl(Duration.ofMinutes(getLong(settings, "option_b_token_ttl_minutes", 5)))
                .enrollmentTokenTtl(Duration.ofMinutes(getLong(settings, "enrollment_token_ttl_minutes", 10)))
                .enrollmentMaxAttempts((int) getLong(settings, "enrollment_max_attempts", 5))
                .allowFallback(getBool(settings, "allow_fallback", true))
                .freezePacketLimitPerSecond((int) getLong(settings, "freeze_packet_limit_per_second", 20))
                .trustedDeviceWindow(Duration.ofMinutes(getLong(settings, "trusted_device_window_minutes", 15)))
                .stripVanillaOp(getBool(settings, "strip_vanilla_op", true))
                .failClosed(getBool(settings, "fail_closed", true))
                .signatureDomain(getString(security, "signature_domain", "MC-ZEROTRUST-AUTH-v1:"))
                .startupSelfTest(getBool(security, "startup_self_test", true))
                .notifyCooldown(Duration.ofSeconds(getLong(discord, "notify_cooldown_seconds", 60)))
                .logRetentionDays((int) getLong(logging, "retention_days", 90))
                .build();

        Set<UUID> admins = new HashSet<>();
        Object adminsObj = top.get("admins");
        if (adminsObj instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    Object u = m.get("uuid");
                    if (u != null) {
                        try {
                            admins.add(UUID.fromString(u.toString().trim()));
                        } catch (IllegalArgumentException ignored) {
                            // 跳過畸形 UUID。
                        }
                    }
                }
            }
        }

        String node = getString(permissions, "admin_node", "zerotrust.admin");
        String discordId = getString(discord, "admin_discord_id", "");
        String fallback = getString(discord, "fallback_channel_id", "");

        return new Loaded(config, admins, node, discordId, fallback);
    }

    // ── 解析輔助 ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> top, String key) {
        Object v = top.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static long getLong(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.longValue();
        if (v != null) {
            try { return Long.parseLong(v.toString().trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static boolean getBool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        if (v instanceof Boolean b) return b;
        if (v != null) return Boolean.parseBoolean(v.toString().trim());
        return def;
    }

    private static String getString(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : v.toString();
    }
}

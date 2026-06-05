package com.chococar.zerotrust.paper;

import com.chococar.zerotrust.config.ZeroTrustConfig;
import com.chococar.zerotrust.platform.StoredKey;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 將 Bukkit {@link FileConfiguration}（{@code config.yml}）解析為核心所需的
 * {@link ZeroTrustConfig}，並額外擷取平台層需要的欄位（管理員 UUID 集合、
 * 每位管理員的公鑰、Discord 設定、權限 node）。
 *
 * <p>秘密（Bot Token、IP HMAC 密鑰鹽）<b>不</b>在此解析，一律由環境變數注入（計劃 6.2）。
 *
 * <p>本類別不可變、無平台副作用，僅做純粹解析；故 fail-closed 的責任落在呼叫端
 * （{@link ZeroTrustPlugin}）：解析失敗應導致安全模式而非放行。
 */
final class ConfigLoader {

    private ConfigLoader() {}

    /** 解析結果：核心設定 + 平台層額外欄位。 */
    static final class Loaded {
        final ZeroTrustConfig config;
        /** 受保護的管理員帳號 UUID 集合（來自 {@code admins[].uuid}）。 */
        final List<UUID> admins;
        /** 每位管理員的公鑰清單（供 {@link YamlKeyRepository} 與展示）。 */
        final Map<UUID, List<StoredKey>> keys;
        /** Discord 管理員使用者 ID（DM 對象）；可能為空字串。 */
        final String discordAdminId;
        /** Discord 後備頻道 ID（DM 失敗時）；可能為空字串。 */
        final String discordFallbackChannelId;
        /** 授予的權限 node（LuckPerms transient 或 Bukkit attachment）。 */
        final String adminPermissionNode;
        /** 是否嚴格要求 LuckPerms 作為權限後端（預設 false：允許 Bukkit attachment 後備）。 */
        final boolean requireLuckPerms;

        Loaded(ZeroTrustConfig config,
               List<UUID> admins,
               Map<UUID, List<StoredKey>> keys,
               String discordAdminId,
               String discordFallbackChannelId,
               String adminPermissionNode,
               boolean requireLuckPerms) {
            this.config = config;
            this.admins = Collections.unmodifiableList(admins);
            this.keys = Collections.unmodifiableMap(keys);
            this.discordAdminId = discordAdminId;
            this.discordFallbackChannelId = discordFallbackChannelId;
            this.adminPermissionNode = adminPermissionNode;
            this.requireLuckPerms = requireLuckPerms;
        }
    }

    /** 預設權限 node（無 LuckPerms 時授予的 Bukkit 權限）。 */
    static final String DEFAULT_ADMIN_PERMISSION_NODE = "zerotrust.admin";

    /**
     * 解析設定。對於缺漏的 key 一律套用計劃 7.3 的預設值。
     *
     * @param yaml Bukkit 已載入的設定
     * @param log  記錄解析警告（不含任何秘密）
     */
    static Loaded load(FileConfiguration yaml, Logger log) {
        ZeroTrustConfig.Builder b = ZeroTrustConfig.builder();

        // ── settings.* ──（單位：hours / minutes / seconds，依 YAML）
        b.sessionTtl(Duration.ofHours(getLong(yaml, "settings.session_ttl_hours", 4)));
        b.maxAttempts((int) getLong(yaml, "settings.max_attempts", 3));
        b.optionATimeout(Duration.ofSeconds(getLong(yaml, "settings.option_a_timeout_seconds", 10)));
        b.optionBTokenTtl(Duration.ofMinutes(getLong(yaml, "settings.option_b_token_ttl_minutes", 5)));
        b.enrollmentTokenTtl(Duration.ofMinutes(getLong(yaml, "settings.enrollment_token_ttl_minutes", 10)));
        b.enrollmentMaxAttempts((int) getLong(yaml, "settings.enrollment_max_attempts", 5));
        b.allowFallback(yaml.getBoolean("settings.allow_fallback", true));
        b.freezePacketLimitPerSecond((int) getLong(yaml, "settings.freeze_packet_limit_per_second", 20));
        b.trustedDeviceWindow(Duration.ofMinutes(getLong(yaml, "settings.trusted_device_window_minutes", 15)));
        b.stripVanillaOp(yaml.getBoolean("settings.strip_vanilla_op", true));
        // fail_closed 不可關閉（計劃 1.2）；即便設定檔寫 false，核心 SelfTest 仍會擋下，
        // 但我們在此尊重設定值，讓 SelfTest 能如實偵測並進入安全模式。
        b.failClosed(yaml.getBoolean("settings.fail_closed", true));

        // ── security.* ──
        String signatureDomain = yaml.getString("security.signature_domain", "MC-ZEROTRUST-AUTH-v1:");
        if (signatureDomain == null || signatureDomain.isBlank()) {
            // 不要靜默改成預設——讓核心 SelfTest 偵測到空白並 fail-closed。
            signatureDomain = "";
        }
        b.signatureDomain(signatureDomain);
        b.startupSelfTest(yaml.getBoolean("security.startup_self_test", true));

        // ── discord.* ──
        b.notifyCooldown(Duration.ofSeconds(getLong(yaml, "discord.notify_cooldown_seconds", 60)));
        String discordAdminId = trimToEmpty(yaml.getString("discord.admin_discord_id", ""));
        String discordFallbackChannelId = trimToEmpty(yaml.getString("discord.fallback_channel_id", ""));
        // 佔位符不視為真實值。
        if (isPlaceholder(discordAdminId)) discordAdminId = "";
        if (isPlaceholder(discordFallbackChannelId)) discordFallbackChannelId = "";

        // ── logging.* ──
        b.logRetentionDays((int) getLong(yaml, "logging.retention_days", 90));

        // ── 權限後端設定（平台層）──
        String permNode = trimToEmpty(yaml.getString("permissions.admin_node", DEFAULT_ADMIN_PERMISSION_NODE));
        if (permNode.isEmpty()) permNode = DEFAULT_ADMIN_PERMISSION_NODE;
        boolean requireLuckPerms = yaml.getBoolean("permissions.require_luckperms", false);

        // ── admins[] 與多金鑰 ──
        List<UUID> admins = new ArrayList<>();
        Map<UUID, List<StoredKey>> keys = new LinkedHashMap<>();
        parseAdmins(yaml, log, admins, keys);

        ZeroTrustConfig config = b.build();
        return new Loaded(config, admins, keys, discordAdminId, discordFallbackChannelId, permNode, requireLuckPerms);
    }

    private static void parseAdmins(FileConfiguration yaml,
                                    Logger log,
                                    List<UUID> admins,
                                    Map<UUID, List<StoredKey>> keys) {
        List<Map<?, ?>> adminList = yaml.getMapList("admins");
        if (adminList == null || adminList.isEmpty()) {
            return;
        }
        for (Map<?, ?> entry : adminList) {
            Object rawUuid = entry.get("uuid");
            if (rawUuid == null) continue;
            String uuidStr = String.valueOf(rawUuid).trim();
            if (uuidStr.isEmpty() || isPlaceholder(uuidStr)) continue;

            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException ex) {
                log.warning("略過無效的管理員 UUID：" + uuidStr);
                continue;
            }
            if (!admins.contains(uuid)) {
                admins.add(uuid);
            }

            List<StoredKey> keyList = parseKeys(entry.get("keys"), uuid, log);
            keys.put(uuid, keyList);
        }
    }

    private static List<StoredKey> parseKeys(Object rawKeys, UUID uuid, Logger log) {
        List<StoredKey> result = new ArrayList<>();
        if (!(rawKeys instanceof List<?> list)) {
            return result;
        }
        for (Object k : list) {
            if (!(k instanceof Map<?, ?> km)) continue;
            String label = stringOrNull(km.get("label"));
            String publicKey = stringOrNull(km.get("public_key"));
            String source = stringOrNull(km.get("source"));
            String lastUsedStr = stringOrNull(km.get("last_used"));

            if (publicKey == null || publicKey.isBlank() || isPlaceholder(publicKey)) {
                // 佔位符或空白公鑰：略過（不算錯誤，範例設定常見）。
                continue;
            }
            if (label == null || label.isBlank()) label = "default";
            if (source == null || source.isBlank()) source = "generated";

            Instant lastUsed = null;
            if (lastUsedStr != null && !lastUsedStr.isBlank()) {
                try {
                    lastUsed = Instant.parse(lastUsedStr.trim());
                } catch (DateTimeParseException ex) {
                    log.warning("管理員 " + uuid + " 的金鑰 last_used 時間格式無效：" + lastUsedStr);
                }
            }
            result.add(new StoredKey(label, publicKey.trim(), source, lastUsed));
        }
        return result;
    }

    // ── 小工具 ──────────────────────────────────────────────

    private static long getLong(FileConfiguration yaml, String path, long def) {
        return yaml.getLong(path, def);
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** 判斷是否為範例佔位符（如 {@code <YOUR_...>}、{@code xxxxxxxx-...}）。 */
    private static boolean isPlaceholder(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;
        if (t.startsWith("<") && t.endsWith(">")) return true;
        // 範例 UUID / 公鑰常以一連串 x 或省略號表示。
        if (t.matches("(?i)x{4,}.*")) return true;
        if (t.contains("...")) return true;
        if (t.contains("（") || t.contains("）")) return true; // 全形括號註解殘留
        return false;
    }
}

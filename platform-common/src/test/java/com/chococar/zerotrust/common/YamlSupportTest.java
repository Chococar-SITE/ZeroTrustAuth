package com.chococar.zerotrust.common;

import com.chococar.zerotrust.platform.StoredKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlSupportTest {

    @Test
    void defaultYamlParsesToPlanDefaults() {
        YamlConfigLoader.Loaded l = YamlConfigLoader.parse(YamlConfigLoader.DEFAULT_YAML);
        assertEquals(Duration.ofHours(4), l.config.sessionTtl());
        assertEquals(3, l.config.maxAttempts());
        assertEquals(Duration.ofSeconds(10), l.config.optionATimeout());
        assertEquals(Duration.ofMinutes(15), l.config.trustedDeviceWindow());
        assertTrue(l.config.failClosed());
        assertEquals("MC-ZEROTRUST-AUTH-v1:", l.config.signatureDomain());
        assertEquals(90, l.config.logRetentionDays());
        assertEquals("zerotrust.admin", l.adminPermissionNode);
        assertTrue(l.admins.isEmpty());
    }

    @Test
    void parsesAdminsAndOverrides() {
        String uuid = "11111111-2222-3333-4444-555555555555";
        String yaml = ""
                + "admins:\n"
                + "  - uuid: \"" + uuid + "\"\n"
                + "settings:\n"
                + "  session_ttl_hours: 8\n"
                + "  allow_fallback: false\n"
                + "  max_attempts: 5\n"
                + "security:\n"
                + "  signature_domain: \"MC-ZEROTRUST-AUTH-v2:\"\n"
                + "discord:\n"
                + "  admin_discord_id: \"123\"\n"
                + "  fallback_channel_id: \"456\"\n";
        YamlConfigLoader.Loaded l = YamlConfigLoader.parse(yaml);
        assertEquals(Duration.ofHours(8), l.config.sessionTtl());
        assertFalse(l.config.allowFallback());
        assertEquals(5, l.config.maxAttempts());
        assertEquals("MC-ZEROTRUST-AUTH-v2:", l.config.signatureDomain());
        assertEquals("123", l.discordAdminId);
        assertEquals("456", l.discordFallbackChannelId);
        assertTrue(l.admins.contains(UUID.fromString(uuid)));
    }

    @Test
    void keyRepositoryRoundTrips(@TempDir Path dir) {
        Path keys = dir.resolve("keys.yml");
        UUID u = UUID.randomUUID();
        YamlKeyRepository repo = new YamlKeyRepository(keys, null);
        assertTrue(repo.loadAll().isEmpty());

        Instant now = Instant.parse("2026-06-05T14:32:11Z");
        repo.save(u, List.of(
                new StoredKey("desktop", "MCowBQYDK2VwAyEABASE64==", "generated", now),
                new StoredKey("laptop", "MCowBQYDK2VwAyEABASE64xx", "ssh", null)));

        // Re-open from disk → persisted.
        YamlKeyRepository reopened = new YamlKeyRepository(keys, null);
        Map<UUID, List<StoredKey>> all = reopened.loadAll();
        assertEquals(1, all.size());
        List<StoredKey> list = all.get(u);
        assertEquals(2, list.size());
        assertEquals("desktop", list.get(0).label());
        assertEquals("generated", list.get(0).source());
        assertEquals(now, list.get(0).lastUsed());
        assertEquals("ssh", list.get(1).source());

        // Removing all keys for a uuid drops the entry.
        reopened.save(u, List.of());
        assertTrue(new YamlKeyRepository(keys, null).loadAll().isEmpty());
    }
}

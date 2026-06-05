package com.chococar.zerotrust.auth;

import com.chococar.zerotrust.platform.StoredKey;
import com.chococar.zerotrust.support.CryptoTestKit;
import com.chococar.zerotrust.support.InMemoryKeyRepository;
import com.chococar.zerotrust.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicKeyStoreTest {

    private static final String DOMAIN = "MC-ZEROTRUST-AUTH-v1:";
    private InMemoryKeyRepository repo;
    private PublicKeyStore store;
    private MutableClock clock;
    private final Ed25519Verifier verifier = new Ed25519Verifier();
    private final UUID uuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = new InMemoryKeyRepository();
        clock = MutableClock.atEpoch();
        store = new PublicKeyStore(repo, verifier, clock);
    }

    private byte[] nonce() {
        byte[] n = new byte[32];
        for (int i = 0; i < n.length; i++) n[i] = (byte) (i * 7);
        return n;
    }

    @Test
    void addValidKeyAndQuery() {
        KeyPair kp = CryptoTestKit.generateEd25519();
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(kp.getPublic()), "generated", "desktop");
        assertTrue(store.hasKeys(uuid));
        assertEquals(1, store.getStoredKeys(uuid).size());
        assertEquals("desktop", store.getStoredKeys(uuid).get(0).label());
    }

    @Test
    void addPersistsViaRepo() {
        KeyPair kp = CryptoTestKit.generateEd25519();
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(kp.getPublic()), "generated", "desktop");
        assertTrue(repo.saveCount.get() >= 1, "addKey 應觸發 repo.save");
        assertEquals(1, repo.current(uuid).size());
    }

    @Test
    void rejectBadKey() {
        assertThrows(IllegalArgumentException.class,
                () -> store.addKey(uuid, CryptoTestKit.rsaPublicKeyBase64(), "generated", "x"));
        assertFalse(store.hasKeys(uuid));
    }

    @Test
    void twoLabelsBothVerify() {
        KeyPair desk = CryptoTestKit.generateEd25519();
        KeyPair lap = CryptoTestKit.generateEd25519();
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(desk.getPublic()), "generated", "desktop");
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(lap.getPublic()), "ssh", "laptop");
        assertEquals(2, store.getStoredKeys(uuid).size());

        byte[] nonce = nonce();
        byte[] sigDesk = CryptoTestKit.sign(desk.getPrivate(), DOMAIN, nonce);
        byte[] sigLap = CryptoTestKit.sign(lap.getPrivate(), DOMAIN, nonce);
        assertTrue(store.verifyAgainstAnyKey(uuid, DOMAIN, nonce, sigDesk).isPresent());
        assertTrue(store.verifyAgainstAnyKey(uuid, DOMAIN, nonce, sigLap).isPresent());
    }

    @Test
    void sameLabelReplaces() {
        KeyPair first = CryptoTestKit.generateEd25519();
        KeyPair second = CryptoTestKit.generateEd25519();
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(first.getPublic()), "generated", "desktop");
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(second.getPublic()), "generated", "desktop");
        assertEquals(1, store.getStoredKeys(uuid).size());

        byte[] nonce = nonce();
        // 舊金鑰簽名不再有效；新金鑰有效。
        byte[] sigOld = CryptoTestKit.sign(first.getPrivate(), DOMAIN, nonce);
        byte[] sigNew = CryptoTestKit.sign(second.getPrivate(), DOMAIN, nonce);
        assertTrue(store.verifyAgainstAnyKey(uuid, DOMAIN, nonce, sigOld).isEmpty());
        assertTrue(store.verifyAgainstAnyKey(uuid, DOMAIN, nonce, sigNew).isPresent());
    }

    @Test
    void removeOneKeepsOther() {
        KeyPair desk = CryptoTestKit.generateEd25519();
        KeyPair lap = CryptoTestKit.generateEd25519();
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(desk.getPublic()), "generated", "desktop");
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(lap.getPublic()), "ssh", "laptop");

        assertTrue(store.removeKey(uuid, "desktop"));
        assertEquals(1, store.getStoredKeys(uuid).size());
        assertEquals("laptop", store.getStoredKeys(uuid).get(0).label());
    }

    @Test
    void removeNonexistentLabelReturnsFalse() {
        KeyPair kp = CryptoTestKit.generateEd25519();
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(kp.getPublic()), "generated", "desktop");
        assertFalse(store.removeKey(uuid, "nope"));
    }

    @Test
    void removeAll() {
        KeyPair desk = CryptoTestKit.generateEd25519();
        KeyPair lap = CryptoTestKit.generateEd25519();
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(desk.getPublic()), "generated", "desktop");
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(lap.getPublic()), "ssh", "laptop");
        store.removeAll(uuid);
        assertFalse(store.hasKeys(uuid));
        assertTrue(store.getStoredKeys(uuid).isEmpty());
    }

    @Test
    void verifyAgainstAnyKeyUpdatesLastUsed() {
        KeyPair kp = CryptoTestKit.generateEd25519();
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(kp.getPublic()), "generated", "desktop");
        // 推進時鐘，使 last-used 為一個可辨識的時間。
        clock.advance(java.time.Duration.ofMinutes(5));
        byte[] nonce = nonce();
        byte[] sig = CryptoTestKit.sign(kp.getPrivate(), DOMAIN, nonce);

        int savesBefore = repo.saveCount.get();
        Optional<String> fp = store.verifyAgainstAnyKey(uuid, DOMAIN, nonce, sig);
        assertTrue(fp.isPresent());
        assertNotNull(fp.get());
        // last-used 應已更新並落盤。
        assertTrue(repo.saveCount.get() > savesBefore);
        StoredKey k = store.getStoredKeys(uuid).get(0);
        assertNotNull(k.lastUsed());
        assertEquals(clock.instant(), k.lastUsed());
    }

    @Test
    void verifyWithNoKeysReturnsEmpty() {
        byte[] nonce = nonce();
        assertTrue(store.verifyAgainstAnyKey(uuid, DOMAIN, nonce, new byte[64]).isEmpty());
    }

    @Test
    void fingerprintIsStableSha256Hex() {
        KeyPair kp = CryptoTestKit.generateEd25519();
        String fp1 = store.fingerprint(kp.getPublic());
        String fp2 = store.fingerprint(kp.getPublic());
        assertEquals(fp1, fp2);
        assertEquals(64, fp1.length()); // SHA-256 hex
        assertTrue(fp1.matches("[0-9a-f]{64}"));
    }

    @Test
    void loadsExistingKeysAtConstruction() {
        KeyPair kp = CryptoTestKit.generateEd25519();
        UUID seeded = UUID.randomUUID();
        repo.seed(seeded, new StoredKey("desktop",
                CryptoTestKit.encodePublicKeyBase64(kp.getPublic()), "generated", null));
        // 重新建構（模擬重啟載入）。
        PublicKeyStore reloaded = new PublicKeyStore(repo, verifier, clock);
        assertTrue(reloaded.hasKeys(seeded));
        List<StoredKey> keys = reloaded.getStoredKeys(seeded);
        assertEquals(1, keys.size());
    }

    @Test
    void defaultLabelWhenNull() {
        KeyPair kp = CryptoTestKit.generateEd25519();
        store.addKey(uuid, CryptoTestKit.encodePublicKeyBase64(kp.getPublic()), "generated", null);
        assertEquals("default", store.getStoredKeys(uuid).get(0).label());
    }
}

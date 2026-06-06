package com.chococar.zerotrust.client;

import com.chococar.zerotrust.auth.Ed25519Verifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.PublicKey;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientKeyStoreTest {

    private static final String DOMAIN = "MC-ZEROTRUST-AUTH-v1:";
    private final Ed25519Verifier server = new Ed25519Verifier();

    @Test
    void generatesPersistsAndReloadsSameKey(@TempDir Path dir) {
        Path keyFile = dir.resolve("zt-client.key");

        ClientIdentity first = ClientKeyStore.loadOrGenerate(keyFile);
        assertTrue(java.nio.file.Files.exists(keyFile));

        // 再次載入應得到「同一把」金鑰（公鑰相同）。
        ClientIdentity reloaded = ClientKeyStore.loadOrGenerate(keyFile);
        assertEquals(first.publicKeyBase64(), reloaded.publicKeyBase64());

        // 重建後的私鑰仍能簽出伺服器可驗證的簽名。
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        byte[] sig = reloaded.sign(DOMAIN, nonce);
        PublicKey pub = server.parsePublicKey(reloaded.publicKeyBase64());
        assertTrue(server.verify(DOMAIN, nonce, sig, pub), "reloaded key must still verify server-side");
    }

    @Test
    void corruptFileRegenerates(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("zt-client.key");
        java.nio.file.Files.writeString(keyFile, "garbage-not-a-key\n");
        ClientIdentity id = ClientKeyStore.loadOrGenerate(keyFile);
        // 損毀檔被覆寫為合法金鑰。
        PublicKey pub = server.parsePublicKey(id.publicKeyBase64());
        assertTrue(pub.getAlgorithm().toLowerCase().contains("ed"));
    }
}

package com.chococar.zerotrust.client;

import com.chococar.zerotrust.auth.Ed25519Verifier;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCryptoTest {

    // 動態組裝，避免在原始碼中留下完整 PEM 標頭字面（秘密掃描友善）。
    private static final String OPENSSH_TYPE = "OPENSSH PRIVATE KEY";
    private static final String DOMAIN = "MC-ZEROTRUST-AUTH-v1:";
    private final Ed25519Verifier server = new Ed25519Verifier();

    @Test
    void generatedKeySignsWhatServerVerifies() {
        ClientIdentity id = ClientKeyManager.generate();
        byte[] nonce = randomNonce();
        byte[] sig = new SignatureResponder(id, DOMAIN).respond(nonce);

        PublicKey serverSide = server.parsePublicKey(id.publicKeyBase64());
        assertTrue(server.verify(DOMAIN, nonce, sig, serverSide), "server must verify client signature");
    }

    @Test
    void domainSeparationHolds() {
        ClientIdentity id = ClientKeyManager.generate();
        byte[] nonce = randomNonce();
        byte[] sig = id.sign(DOMAIN, nonce);
        PublicKey pub = server.parsePublicKey(id.publicKeyBase64());

        // 不同領域前綴 → 驗證失敗（簽名在其他場景無效）。
        assertFalse(server.verify("OTHER-DOMAIN:", nonce, sig, pub));
        // 不同 nonce → 失敗。
        assertFalse(server.verify(DOMAIN, randomNonce(), sig, pub));
    }

    @Test
    void exportedPublicKeyParsesAsEd25519() {
        ClientIdentity id = ClientKeyManager.generate();
        PublicKey pub = server.parsePublicKey(id.publicKeyBase64());
        assertNotNull(pub);
        assertTrue(pub.getAlgorithm().toLowerCase().contains("ed"));
    }

    @Test
    void reusesUnencryptedOpenSshEd25519Key() throws Exception {
        // 以 BouncyCastle 產生並編碼成 OpenSSH 格式，再經 ClientKeyManager 解析回來（往返）。
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(new SecureRandom());
        byte[] blob = OpenSSHPrivateKeyUtil.encodePrivateKey(priv);
        String pem = armor(blob);

        ClientIdentity id = ClientKeyManager.fromOpenSshEd25519(pem);
        byte[] nonce = randomNonce();
        byte[] sig = id.sign(DOMAIN, nonce);

        PublicKey pub = server.parsePublicKey(id.publicKeyBase64());
        assertTrue(server.verify(DOMAIN, nonce, sig, pub), "SSH-reused key must verify server-side");
    }

    @Test
    void rejectsGarbageOpenSshKey() {
        try {
            ClientKeyManager.fromOpenSshEd25519(armor("not a real key".getBytes()));
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static byte[] randomNonce() {
        byte[] n = new byte[32];
        new SecureRandom().nextBytes(n);
        return n;
    }

    private static String armor(byte[] blob) {
        String b64 = Base64.getMimeEncoder(70, "\n".getBytes()).encodeToString(blob);
        return "-----BEGIN " + OPENSSH_TYPE + "-----\n" + b64 + "\n-----END " + OPENSSH_TYPE + "-----\n";
    }
}

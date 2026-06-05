package com.chococar.zerotrust.auth;

import com.chococar.zerotrust.support.CryptoTestKit;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ed25519VerifierTest {

    private static final String DOMAIN = "MC-ZEROTRUST-AUTH-v1:";
    private final Ed25519Verifier verifier = new Ed25519Verifier();

    private byte[] nonce() {
        byte[] n = new byte[32];
        for (int i = 0; i < n.length; i++) n[i] = (byte) i;
        return n;
    }

    @Test
    void signVerifyRoundtrip() {
        KeyPair kp = CryptoTestKit.generateEd25519();
        byte[] nonce = nonce();
        byte[] sig = CryptoTestKit.sign(kp.getPrivate(), DOMAIN, nonce);
        assertTrue(verifier.verify(DOMAIN, nonce, sig, kp.getPublic()));
    }

    @Test
    void parseAndVerifyWithBase64EncodedKey() {
        KeyPair kp = CryptoTestKit.generateEd25519();
        String b64 = CryptoTestKit.encodePublicKeyBase64(kp.getPublic());
        PublicKey parsed = verifier.parsePublicKey(b64);
        assertNotNull(parsed);
        byte[] nonce = nonce();
        byte[] sig = CryptoTestKit.sign(kp.getPrivate(), DOMAIN, nonce);
        assertTrue(verifier.verify(DOMAIN, nonce, sig, parsed));
    }

    @Test
    void domainSeparation_signatureOverRawNonceFails() {
        // 客戶端若簽「裸 Nonce」（未經領域分隔），伺服器驗證必須失敗（封堵簽名預言機）。
        KeyPair kp = CryptoTestKit.generateEd25519();
        byte[] nonce = nonce();
        byte[] rawSig = CryptoTestKit.signRaw(kp.getPrivate(), nonce);
        assertFalse(verifier.verify(DOMAIN, nonce, rawSig, kp.getPublic()));
    }

    @Test
    void domainSeparation_differentDomainFails() {
        KeyPair kp = CryptoTestKit.generateEd25519();
        byte[] nonce = nonce();
        byte[] sig = CryptoTestKit.sign(kp.getPrivate(), "OTHER-DOMAIN:", nonce);
        assertFalse(verifier.verify(DOMAIN, nonce, sig, kp.getPublic()));
    }

    @Test
    void tamperedSignatureFails() {
        KeyPair kp = CryptoTestKit.generateEd25519();
        byte[] nonce = nonce();
        byte[] sig = CryptoTestKit.sign(kp.getPrivate(), DOMAIN, nonce);
        sig[0] ^= 0x01;
        assertFalse(verifier.verify(DOMAIN, nonce, sig, kp.getPublic()));
    }

    @Test
    void tamperedNonceFails() {
        KeyPair kp = CryptoTestKit.generateEd25519();
        byte[] nonce = nonce();
        byte[] sig = CryptoTestKit.sign(kp.getPrivate(), DOMAIN, nonce);
        byte[] otherNonce = nonce();
        otherNonce[5] ^= 0x01;
        assertFalse(verifier.verify(DOMAIN, otherNonce, sig, kp.getPublic()));
    }

    @Test
    void rejectsRsaPublicKey() {
        String rsa = CryptoTestKit.rsaPublicKeyBase64();
        assertThrows(IllegalArgumentException.class, () -> verifier.parsePublicKey(rsa));
    }

    @Test
    void rejectsMalformedBase64() {
        assertThrows(IllegalArgumentException.class, () -> verifier.parsePublicKey("not!base64!!!"));
    }

    @Test
    void rejectsEmptyKey() {
        assertThrows(IllegalArgumentException.class, () -> verifier.parsePublicKey(""));
        assertThrows(IllegalArgumentException.class, () -> verifier.parsePublicKey("   "));
    }

    @Test
    void rejectsValidBase64ButGarbageDer() {
        String garbage = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5});
        assertThrows(IllegalArgumentException.class, () -> verifier.parsePublicKey(garbage));
    }

    @Test
    void signedMessageIsSha512OfDomainAndNonce() {
        byte[] nonce = nonce();
        byte[] expected = CryptoTestKit.signedMessage(DOMAIN, nonce);
        byte[] actual = verifier.signedMessage(DOMAIN, nonce);
        assertArrayEquals(expected, actual);
        // SHA-512 → 64 bytes。
        assertTrue(actual.length == 64);
    }
}

package com.chococar.zerotrust.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 客戶端金鑰的本機持久化（選項 A）。私鑰<b>永不離開本機</b>。
 *
 * <p>檔案格式為兩行：{@code pub:<base64-x509>} 與 {@code priv:<base64-pkcs8>}，
 * 並盡力將檔案權限收斂為僅擁有者可讀寫。
 *
 * <p><b>限制（follow-up）</b>：目前私鑰<b>未加密</b>儲存（僅靠檔案權限保護）。計劃 6.2 要求
 * AES-256-GCM＋PBKDF2（passphrase）加密——屬硬化項目，待客戶端 Mod 的 passphrase 來源
 * （設定／提示）確立後補上。建議搭配 {@link ClientKeyManager#fromOpenSshEd25519} 的 SSH 模式
 * （金鑰由 OpenSSH 既有保護管理）作為替代。
 */
public final class ClientKeyStore {

    private ClientKeyStore() {}

    /** 載入既有金鑰；不存在或損毀則產生新金鑰並儲存。 */
    public static ClientIdentity loadOrGenerate(Path keyFile) {
        if (keyFile != null && Files.exists(keyFile)) {
            try {
                ClientIdentity existing = load(keyFile);
                if (existing != null) {
                    return existing;
                }
            } catch (RuntimeException ignored) {
                // 損毀 → 重新產生（覆寫）。
            }
        }
        ClientIdentity id = ClientKeyManager.generate();
        save(keyFile, id);
        return id;
    }

    /** 從金鑰檔載入；缺欄位回傳 {@code null}。 */
    public static ClientIdentity load(Path keyFile) {
        try {
            List<String> lines = Files.readAllLines(keyFile, StandardCharsets.UTF_8);
            String pub = null;
            String priv = null;
            for (String line : lines) {
                String l = line.trim();
                if (l.startsWith("pub:")) {
                    pub = l.substring("pub:".length()).trim();
                } else if (l.startsWith("priv:")) {
                    priv = l.substring("priv:".length()).trim();
                }
            }
            if (pub == null || pub.isEmpty() || priv == null || priv.isEmpty()) {
                return null;
            }
            return ClientKeyManager.fromStored(pub, priv);
        } catch (IOException e) {
            throw new UncheckedIOException("無法讀取客戶端金鑰：" + keyFile, e);
        }
    }

    /** 儲存金鑰（覆寫），並盡力收斂檔案權限為僅擁有者。 */
    public static void save(Path keyFile, ClientIdentity id) {
        try {
            Path parent = keyFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String content = "pub:" + id.publicKeyBase64() + "\n"
                    + "priv:" + id.privateKeyPkcs8Base64() + "\n";
            Files.writeString(keyFile, content, StandardCharsets.UTF_8);
            restrictPermissions(keyFile);
        } catch (IOException e) {
            throw new UncheckedIOException("無法寫入客戶端金鑰：" + keyFile, e);
        }
    }

    /** 盡力設為僅擁有者可讀寫（私鑰保護）。跨平台差異時靜默略過。 */
    private static void restrictPermissions(Path keyFile) {
        try {
            java.io.File f = keyFile.toFile();
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setExecutable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
        } catch (RuntimeException ignored) {
            // 權限 API 在某些檔案系統可能不適用。
        }
    }
}

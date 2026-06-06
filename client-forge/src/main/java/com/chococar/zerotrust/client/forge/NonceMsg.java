package com.chococar.zerotrust.client.forge;

import com.chococar.zerotrust.client.ClientIdentity;
import com.chococar.zerotrust.client.ClientKeyStore;
import com.chococar.zerotrust.client.SignatureResponder;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 選項 A 挑戰 / 回應封包的 <b>客戶端</b> 對應（計劃 5.6），**Forge 1.19.2 版本**。
 *
 * <h2>與伺服器 {@code platform-forge} 的 {@code NonceMsg} 完全對齊</h2>
 * 相同通道 {@code zerotrustauth:auth}、相同協定版本 {@code "1"}、相同訊息 discriminator {@code 0}、
 * 相同 encode/decode（VarInt 長度前綴的 byte 陣列）。如此雙端的 {@link SimpleChannel} 訊息註冊表一致，
 * 封包可正確跨線解碼。
 *
 * <h2>方向與承載</h2>
 * <ul>
 *   <li><b>S2C（伺服器 → 客戶端，挑戰）：</b>byte 陣列 = 32-byte Nonce。本客戶端 mod 於
 *       {@link #handle} 偵測「在客戶端收到」→ 加領域前綴簽名 → 在同一通道送回回應。</li>
 *   <li><b>C2S（客戶端 → 伺服器，回應）：</b>byte 陣列 = {@code nonceLen(1 byte) || nonce || signature}
 *       （與 Paper / Fabric 的選項 A 回應慣例一致）。伺服器端 {@code NonceMsg.handle} 為 no-op，
 *       會接受此封包而不致崩潰。</li>
 * </ul>
 *
 * <h2>領域分隔（安全不變式 4）</h2>
 * 客戶端<b>絕不</b>簽裸 Nonce：{@link SignatureResponder} 固定簽 {@code SHA-512(domain || nonce)}。
 * {@link #SIGNATURE_DOMAIN} 須與伺服器 {@code security.signature_domain} 一致（預設）。私鑰永不離開本機。
 */
public final class NonceMsg {

    private static final Logger LOG = Logger.getLogger("ZeroTrustAuthClient");

    /** 領域前綴：須與伺服器 {@code security.signature_domain} 一致（預設）。 */
    static final String SIGNATURE_DOMAIN = "MC-ZEROTRUST-AUTH-v1:";

    private static final String DATA_DIR_NAME = "zerotrustauth";
    private static final String KEY_FILE_NAME = "client.key";

    /** 通道 id {@code zerotrustauth:auth}，與伺服器 {@code platform-forge} 的 {@code NonceMsg} 一致。 */
    public static final ResourceLocation CHANNEL_ID =
            new ResourceLocation("zerotrustauth", "auth");

    /** 協定版本（雙端須一致），與伺服器一致為 {@code "1"}。 */
    private static final String PROTOCOL_VERSION = "1";

    /** 通道（建立一次）：名稱、版本供應器、雙端可接受版本判定，與伺服器一致。 */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CHANNEL_ID,
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    /** 承載的 byte 陣列：挑戰時為 Nonce；回應時為 {@code nonceLen || nonce || signature}。 */
    private final byte[] data;

    public NonceMsg(byte[] data) {
        this.data = Objects.requireNonNull(data, "data");
    }

    public byte[] data() {
        return data;
    }

    /**
     * 於 {@code FMLClientSetupEvent} 呼叫一次：在通道上註冊本訊息型別（discriminator 0），
     * encode/decode 與伺服器一致。handler 於客戶端收到 S2C 挑戰時簽名並回傳回應。
     */
    public static void register() {
        CHANNEL.registerMessage(
                0,
                NonceMsg.class,
                NonceMsg::encode,
                NonceMsg::decode,
                NonceMsg::handle);
    }

    /** 編碼：寫出長度前綴（VarInt）的 byte 陣列，與伺服器 {@code encode} 一致。 */
    public static void encode(NonceMsg msg, FriendlyByteBuf buf) {
        buf.writeByteArray(msg.data);
    }

    /** 解碼：讀回長度前綴的 byte 陣列，與伺服器 {@code decode} 一致。 */
    public static NonceMsg decode(FriendlyByteBuf buf) {
        return new NonceMsg(buf.readByteArray());
    }

    /**
     * 處理：僅在<b>客戶端收到</b>（S2C 挑戰）時動作——加領域前綴簽名後，於同一通道送回
     * {@code nonceLen || nonce || signature}。其餘情況（伺服器端、回應方向）為 no-op。
     * 全程捕捉例外，<b>絕不</b>讓客戶端崩潰；簽名 / 送出在 {@code enqueueWork} 內於主執行緒執行。
     */
    public static void handle(NonceMsg msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        try {
            // 僅處理「在客戶端收到」的挑戰；回應方向（伺服器收到）由伺服器端 no-op 處理。
            if (ctx.getDirection().getReceptionSide().isClient()) {
                ctx.enqueueWork(() -> respond(msg.data));
            }
        } catch (Throwable t) {
            LOG.warning("ZeroTrustAuth 處理選項 A 挑戰失敗（不影響客戶端執行）：" + t);
        } finally {
            ctx.setPacketHandled(true);
        }
    }

    /** 對挑戰 Nonce 簽名並送回回應。失敗記錄但不丟例外。 */
    private static void respond(byte[] nonce) {
        try {
            if (nonce == null || nonce.length == 0 || nonce.length > 255) {
                LOG.warning("ZeroTrustAuth 收到畸形挑戰 Nonce（長度非 1..255），忽略。");
                return;
            }
            ClientIdentity id = ClientKeyStore.loadOrGenerate(keyFile());
            byte[] sig = new SignatureResponder(id, SIGNATURE_DOMAIN).respond(nonce);
            byte[] response = buildResponse(nonce, sig);
            CHANNEL.sendToServer(new NonceMsg(response));
            LOG.fine("ZeroTrustAuth 已回應選項 A 挑戰（簽名長度 " + sig.length + "）。");
        } catch (Throwable t) {
            LOG.warning("ZeroTrustAuth 簽名 / 送出選項 A 回應失敗：" + t);
        }
    }

    /** 組裝 C2S 回應 {@code nonceLen(1 byte) || nonce || signature}（與伺服器拆解慣例一致）。 */
    static byte[] buildResponse(byte[] nonce, byte[] signature) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 + nonce.length + signature.length);
        out.write(nonce.length & 0xFF);
        out.write(nonce, 0, nonce.length);
        out.write(signature, 0, signature.length);
        return out.toByteArray();
    }

    /** 公鑰（Base64 X.509），供 {@code /ztclient pubkey} 印出。失敗時向上拋給呼叫端記錄。 */
    static String publicKeyBase64() {
        ClientIdentity id = ClientKeyStore.loadOrGenerate(keyFile());
        return id.publicKeyBase64();
    }

    /** 客戶端金鑰檔：{@code config/zerotrustauth/client.key}。 */
    private static Path keyFile() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve(DATA_DIR_NAME)
                .resolve(KEY_FILE_NAME);
    }
}

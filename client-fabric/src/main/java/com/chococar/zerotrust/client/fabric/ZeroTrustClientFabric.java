package com.chococar.zerotrust.client.fabric;

import com.chococar.zerotrust.client.ClientIdentity;
import com.chococar.zerotrust.client.ClientKeyStore;
import com.chococar.zerotrust.client.SignatureResponder;

import com.mojang.brigadier.CommandDispatcher;

import io.netty.buffer.ByteBuf;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * 選項 A 的 Fabric <b>客戶端</b> Mod 進入點（計劃 5.6）。跑在「管理員的遊戲客戶端」：
 *
 * <ol>
 *   <li>收到伺服器的 Nonce 挑戰封包（通道 {@code zerotrust:auth}）→ 以本機金鑰
 *       <b>加領域前綴</b>（{@link #SIGNATURE_DOMAIN}）簽名 → 回傳回應封包。玩家無需任何操作。</li>
 *   <li>提供 {@code /ztclient pubkey} 客戶端指令，把可上傳的公鑰印到聊天，供玩家於伺服器執行
 *       {@code /authkey upload <pubkey> <code>}。</li>
 * </ol>
 *
 * <h2>領域分隔 / 簽名預言機防護（安全不變式 4）</h2>
 * 客戶端<b>絕不</b>簽裸 Nonce：{@link SignatureResponder} 固定簽 {@code SHA-512(domain || nonce)}，
 * 與伺服器 {@code Ed25519Verifier} 完全一致。{@link #SIGNATURE_DOMAIN} 為伺服器預設值，須與伺服器
 * {@code security.signature_domain} 設定相符（若伺服器自訂前綴，此處亦須同步調整）。
 *
 * <h2>私鑰永不離開本機（安全不變式）</h2>
 * 金鑰由 {@link ClientKeyStore#loadOrGenerate(Path)} 於 {@code config/zerotrustauth/client.key} 管理；
 * 僅<b>公鑰</b>會被印出 / 上傳，網路上只傳<b>簽名</b>，私鑰<b>永不</b>傳輸。
 *
 * <h2>封包格式（與 {@code platform-fabric} 的 {@code AuthPayload} 對齊）</h2>
 * 通道 {@code zerotrust:auth}，承載單一 byte 陣列 {@code data}（{@link ByteBufCodecs#byteArray} 編碼，
 * 上限 {@link #MAX_PAYLOAD_BYTES}）：
 * <ul>
 *   <li><b>S2C（挑戰）：</b>{@code data} = 32-byte Nonce。</li>
 *   <li><b>C2S（回應）：</b>{@code data} = {@code nonceLen(1 byte) || nonce || signature}
 *       （伺服器 {@code ConnectionListener.splitResponse} 以此拆解）。</li>
 * </ul>
 */
public final class ZeroTrustClientFabric implements ClientModInitializer {

    /** 與遊戲載入器無關的 JUL logger（與伺服器端 / client-core 一致風格）。 */
    private static final Logger LOG = Logger.getLogger("ZeroTrustAuthClient");

    /**
     * 領域前綴：須與伺服器 {@code security.signature_domain} 一致（預設 {@code MC-ZEROTRUST-AUTH-v1:}）。
     * 帶版本號供演算法升級（crypto agility）。
     */
    static final String SIGNATURE_DOMAIN = "MC-ZEROTRUST-AUTH-v1:";

    /** 設定 / 金鑰資料夾名（置於客戶端 config/zerotrustauth/）。 */
    private static final String DATA_DIR_NAME = "zerotrustauth";

    /** 金鑰檔名（私鑰永不離開本機；見 {@link ClientKeyStore}）。 */
    private static final String KEY_FILE_NAME = "client.key";

    /** 承載上限（bytes），與伺服器 {@code AuthPayload.MAX_PAYLOAD_BYTES} 一致，防惡意巨量封包。 */
    static final int MAX_PAYLOAD_BYTES = 1024;

    /** 通道 ID {@code zerotrust:auth}，與 {@code platform-fabric} 的 {@code AuthPayload.CHANNEL_ID} 一致。 */
    static final Identifier CHANNEL_ID =
            Identifier.fromNamespaceAndPath("zerotrust", "auth");

    /**
     * 客戶端 payload 型別，鏡像伺服器 {@code AuthPayload}：同一通道、同一 byte 陣列 codec。
     * 同一型別同時用於接收 S2C 挑戰與送出 C2S 回應。
     */
    public record AuthPayload(byte[] data) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<AuthPayload> TYPE =
                new CustomPacketPayload.Type<>(CHANNEL_ID);

        /** 與伺服器一致：byte 陣列（VarInt 長度前綴），上限 {@link #MAX_PAYLOAD_BYTES}。 */
        public static final StreamCodec<ByteBuf, AuthPayload> STREAM_CODEC =
                ByteBufCodecs.byteArray(MAX_PAYLOAD_BYTES).map(AuthPayload::new, AuthPayload::data);

        @Override
        public CustomPacketPayload.Type<AuthPayload> type() {
            return TYPE;
        }
    }

    @Override
    public void onInitializeClient() {
        // 1) 註冊 payload 型別。S2C 用於收挑戰；C2S 用於送回應（雙向皆須註冊，否則送出非法）。
        try {
            PayloadTypeRegistry.playS2C().register(AuthPayload.TYPE, AuthPayload.STREAM_CODEC);
            PayloadTypeRegistry.playC2S().register(AuthPayload.TYPE, AuthPayload.STREAM_CODEC);
        } catch (Throwable t) {
            // 重複註冊或 API 差異不可拖垮客戶端啟動。
            LOG.warning("ZeroTrustAuth 客戶端封包註冊失敗（選項 A 將不可用）：" + t);
        }

        // 2) S2C 挑戰接收器：簽名後回傳。context 於客戶端主執行緒執行（Fabric 保證）。
        try {
            ClientPlayNetworking.registerGlobalReceiver(AuthPayload.TYPE, (payload, context) ->
                    handleChallenge(payload.data()));
        } catch (Throwable t) {
            LOG.warning("ZeroTrustAuth 客戶端挑戰接收器註冊失敗：" + t);
        }

        // 3) /ztclient pubkey 客戶端指令。以 inline lambda 並讓參數型別由 SAM 介面推斷，
        //    避免硬寫第二參數型別（不同 Fabric API 版本可能為 CommandBuildContext 或 CommandRegistryAccess）。
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                registerCommand(dispatcher));

        LOG.info("ZeroTrustAuth 客戶端（Fabric / 選項 A）已初始化。");
    }

    // ── 選項 A：收挑戰 → 簽名 → 回傳 ─────────────────────────

    /**
     * 處理伺服器挑戰：以本機金鑰加領域前綴簽名，回傳 {@code nonceLen || nonce || signature}。
     * 任何金鑰 / 簽名 / 網路例外一律捕捉並記錄，<b>絕不</b>讓客戶端崩潰（逾時後伺服器走選項 B / 嚴格模式）。
     */
    private static void handleChallenge(byte[] nonce) {
        try {
            if (nonce == null || nonce.length == 0 || nonce.length > 255) {
                // nonceLen 以單一 unsigned byte 編碼（伺服器 splitResponse 約定）；超界即視為畸形。
                LOG.warning("ZeroTrustAuth 收到畸形挑戰 Nonce（長度非 1..255），忽略。");
                return;
            }
            Path keyFile = keyFile();
            ClientIdentity id = ClientKeyStore.loadOrGenerate(keyFile);
            byte[] sig = new SignatureResponder(id, SIGNATURE_DOMAIN).respond(nonce);
            byte[] response = buildResponse(nonce, sig);
            ClientPlayNetworking.send(new AuthPayload(response));
            LOG.fine("ZeroTrustAuth 已回應選項 A 挑戰（簽名長度 " + sig.length + "）。");
        } catch (Throwable t) {
            LOG.warning("ZeroTrustAuth 處理選項 A 挑戰失敗（不影響客戶端執行）：" + t);
        }
    }

    /** 組裝 C2S 回應 {@code nonceLen(1 byte) || nonce || signature}（與伺服器 splitResponse 對齊）。 */
    static byte[] buildResponse(byte[] nonce, byte[] signature) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 + nonce.length + signature.length);
        out.write(nonce.length & 0xFF);
        out.write(nonce, 0, nonce.length);
        out.write(signature, 0, signature.length);
        return out.toByteArray();
    }

    // ── /ztclient pubkey ────────────────────────────────────

    /** 註冊 {@code /ztclient pubkey}：把可上傳公鑰印到本地聊天（不送伺服器）。 */
    private void registerCommand(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("ztclient")
                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("pubkey")
                                .executes(ctx -> {
                                    printPubKey(ctx.getSource());
                                    return 1;
                                })));
    }

    /** 載入 / 產生本機金鑰並把公鑰（Base64 X.509）印到聊天。失敗則印錯誤訊息，不丟例外。 */
    private static void printPubKey(FabricClientCommandSource source) {
        try {
            ClientIdentity id = ClientKeyStore.loadOrGenerate(keyFile());
            String pub = id.publicKeyBase64();
            source.sendFeedback(Component.literal(
                    "§a[ZeroTrustAuth] 你的公鑰（在伺服器執行 /authkey upload <pubkey> <code>）："));
            source.sendFeedback(Component.literal("§f" + pub));
        } catch (Throwable t) {
            LOG.warning("ZeroTrustAuth 取得公鑰失敗：" + t);
            source.sendError(Component.literal("§c[ZeroTrustAuth] 無法讀取 / 產生金鑰：" + t.getMessage()));
        }
    }

    /** 客戶端金鑰檔：{@code config/zerotrustauth/client.key}。 */
    private static Path keyFile() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(DATA_DIR_NAME)
                .resolve(KEY_FILE_NAME);
    }
}

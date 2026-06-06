package com.chococar.zerotrust.client.neoforge;

import com.chococar.zerotrust.client.ClientIdentity;
import com.chococar.zerotrust.client.ClientKeyStore;
import com.chococar.zerotrust.client.SignatureResponder;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * 選項 A 的 NeoForge <b>客戶端</b> Mod 進入點（計劃 5.6）。跑在「管理員的遊戲客戶端」：
 *
 * <ol>
 *   <li>收到伺服器的 Nonce 挑戰封包（通道 {@code zerotrust:auth}，{@link AuthChallengePayload}）→
 *       以本機金鑰<b>加領域前綴</b>（{@link #SIGNATURE_DOMAIN}）簽名 →
 *       以 {@link PacketDistributor#sendToServer} 回傳回應。玩家無需任何操作。</li>
 *   <li>提供 {@code /ztclient pubkey} 客戶端指令，把可上傳公鑰印到聊天。</li>
 * </ol>
 *
 * <h2>領域分隔（安全不變式 4）</h2>
 * 客戶端<b>絕不</b>簽裸 Nonce：{@link SignatureResponder} 固定簽 {@code SHA-512(domain || nonce)}。
 * {@link #SIGNATURE_DOMAIN} 須與伺服器 {@code security.signature_domain} 一致（預設值）。
 *
 * <h2>私鑰永不離開本機</h2>
 * 金鑰由 {@link ClientKeyStore} 於 {@code config/zerotrustauth/client.key} 管理；僅<b>公鑰</b>被印出 /
 * 上傳，網路只傳<b>簽名</b>。
 *
 * <h2>封包格式（鏡像 {@code platform-neoforge} 的 {@code AuthChallengePayload}）</h2>
 * 通道 {@code zerotrust:auth}，承載 VarInt 長度前綴的 byte 陣列（{@link ByteBufCodecs#BYTE_ARRAY}）：
 * <ul>
 *   <li><b>S2C（挑戰）：</b>byte 陣列 = 32-byte Nonce。</li>
 *   <li><b>C2S（回應）：</b>byte 陣列 = {@code nonceLen(1 byte) || nonce || signature}
 *       （與 Paper / Fabric 的選項 A 回應慣例一致）。</li>
 * </ul>
 *
 * <p><b>dist 守衛：</b>以 {@code @Mod(dist = Dist.CLIENT)} 限定僅客戶端載入；建構子內再以
 * {@link FMLEnvironment#dist} 二次防護，伺服器端即使誤載亦不接線。
 */
@Mod(value = "zerotrustauthclient", dist = Dist.CLIENT)
public final class ZeroTrustClientNeoForge {

    private static final Logger LOG = Logger.getLogger("ZeroTrustAuthClient");

    /** 領域前綴：須與伺服器 {@code security.signature_domain} 一致（預設）。 */
    static final String SIGNATURE_DOMAIN = "MC-ZEROTRUST-AUTH-v1:";

    private static final String DATA_DIR_NAME = "zerotrustauth";
    private static final String KEY_FILE_NAME = "client.key";

    /** 通道 ID {@code zerotrust:auth}，與 {@code platform-neoforge} 的 {@code AuthChallengePayload} 一致。 */
    static final ResourceLocation CHANNEL_ID =
            ResourceLocation.fromNamespaceAndPath("zerotrust", "auth");

    /**
     * 客戶端 payload 型別，鏡像伺服器 {@code AuthChallengePayload}：同一通道、同一
     * VarInt-長度前綴 byte 陣列 codec。同一型別用於接收 S2C 挑戰與送出 C2S 回應。
     */
    public record AuthChallengePayload(byte[] data) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<AuthChallengePayload> TYPE =
                new CustomPacketPayload.Type<>(CHANNEL_ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, AuthChallengePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BYTE_ARRAY, AuthChallengePayload::data,
                        AuthChallengePayload::new);

        @Override
        public CustomPacketPayload.Type<AuthChallengePayload> type() {
            return TYPE;
        }
    }

    /** NeoForge 以建構子注入 mod 匯流排與容器。 */
    public ZeroTrustClientNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            // 縱深防禦：非客戶端環境一律不接線（伺服器端永不送回應）。
            return;
        }
        // Mod 匯流排：註冊選項 A 封包（雙向，使客戶端可收挑戰並送回應）。
        modEventBus.addListener(this::onRegisterPayloads);
        // 遊戲匯流排：客戶端指令註冊。
        NeoForge.EVENT_BUS.register(this);
        LOG.info("ZeroTrustAuth 客戶端（NeoForge / 選項 A）已載入。");
    }

    // ── Mod 匯流排：封包註冊 ─────────────────────────────────

    /**
     * 註冊選項 A 封包（{@code zerotrust:auth}）為<b>雙向</b>：{@code playBidirectional} 同時允許接收
     * S2C 挑戰與送出 C2S 回應。以 {@code optional()} 註冊以容忍伺服器未宣告對應 payload 的情況。
     */
    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("zerotrustauthclient").optional();
        registrar.playBidirectional(
                AuthChallengePayload.TYPE,
                AuthChallengePayload.STREAM_CODEC,
                (payload, context) -> handleChallenge(payload.data(), context));
    }

    /**
     * 收到伺服器挑戰：加領域前綴簽名後回傳 {@code nonceLen || nonce || signature}。
     * 於客戶端網路執行緒呼叫；以 {@link IPayloadContext#enqueueWork} 切回主執行緒送出，並全程捕捉例外。
     */
    private static void handleChallenge(byte[] nonce, IPayloadContext context) {
        try {
            if (nonce == null || nonce.length == 0 || nonce.length > 255) {
                LOG.warning("ZeroTrustAuth 收到畸形挑戰 Nonce（長度非 1..255），忽略。");
                return;
            }
            byte[] response = signResponse(nonce);
            if (response == null) {
                return;
            }
            context.enqueueWork(() -> {
                try {
                    PacketDistributor.sendToServer(new AuthChallengePayload(response));
                } catch (Throwable t) {
                    LOG.warning("ZeroTrustAuth 送出選項 A 回應失敗：" + t);
                }
            });
        } catch (Throwable t) {
            LOG.warning("ZeroTrustAuth 處理選項 A 挑戰失敗（不影響客戶端執行）：" + t);
        }
    }

    /** 以本機金鑰簽名並組裝回應；失敗回 {@code null}（記錄但不丟例外）。 */
    private static byte[] signResponse(byte[] nonce) {
        try {
            ClientIdentity id = ClientKeyStore.loadOrGenerate(keyFile());
            byte[] sig = new SignatureResponder(id, SIGNATURE_DOMAIN).respond(nonce);
            ByteArrayOutputStream out =
                    new ByteArrayOutputStream(1 + nonce.length + sig.length);
            out.write(nonce.length & 0xFF);
            out.write(nonce, 0, nonce.length);
            out.write(sig, 0, sig.length);
            return out.toByteArray();
        } catch (Throwable t) {
            LOG.warning("ZeroTrustAuth 簽名選項 A 挑戰失敗：" + t);
            return null;
        }
    }

    // ── 遊戲匯流排：客戶端指令 ───────────────────────────────

    /** 註冊 {@code /ztclient pubkey}（客戶端指令，不送伺服器）。 */
    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ztclient")
                .then(Commands.literal("pubkey")
                        .executes(ctx -> {
                            printPubKey(ctx.getSource());
                            return 1;
                        }));
        dispatcher.register(root);
    }

    /** 把可上傳公鑰印到聊天。失敗則印錯誤訊息，不丟例外。 */
    private static void printPubKey(CommandSourceStack source) {
        try {
            ClientIdentity id = ClientKeyStore.loadOrGenerate(keyFile());
            String pub = id.publicKeyBase64();
            source.sendSuccess(() -> Component.literal(
                    "§a[ZeroTrustAuth] 你的公鑰（在伺服器執行 /authkey upload <pubkey> <code>）："), false);
            source.sendSuccess(() -> Component.literal("§f" + pub), false);
        } catch (Throwable t) {
            LOG.warning("ZeroTrustAuth 取得公鑰失敗：" + t);
            source.sendFailure(Component.literal("§c[ZeroTrustAuth] 無法讀取 / 產生金鑰：" + t.getMessage()));
        }
    }

    /** 客戶端金鑰檔：{@code config/zerotrustauth/client.key}。 */
    private static Path keyFile() {
        return FMLPaths.CONFIGDIR.get()
                .resolve(DATA_DIR_NAME)
                .resolve(KEY_FILE_NAME);
    }
}

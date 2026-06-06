package com.chococar.zerotrust.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 選項 A 挑戰封包（計劃 3.1 / 5.2），**Forge 1.19.2 版本**：伺服器送出 32-byte Nonce 至客戶端 Mod，
 * 客戶端加領域前綴（{@code signature_domain + nonce}）後以 Ed25519 簽名回傳。
 *
 * <h2>為何是 SimpleChannel（而非 CustomPacketPayload）</h2>
 * 1.19.2 沒有 1.20.5+ 的原版 {@code CustomPacketPayload} / {@code StreamCodec} /
 * {@code RegisterPayloadHandlersEvent} / {@code PacketDistributor.sendToPlayer(player, payload)} API。
 * 舊版 Forge 的網路層為 {@link SimpleChannel}：以 {@link NetworkRegistry#newSimpleChannel} 建立通道，
 * 再以 {@code registerMessage(id, type, encoder, decoder, handler)} 註冊訊息型別，並以
 * {@code channel.send(PacketDistributor.PLAYER.with(() -> player), msg)} 送出。
 *
 * <p><b>領域分隔（安全不變式 4）：</b>本封包僅承載「裸 Nonce」；領域前綴由<b>客戶端</b>於簽名前加上、
 * 由<b>核心</b>（{@code ZeroTrustCore} 經 {@code signature_domain}）以同一前綴驗證。伺服器端不在此處
 * 對 Nonce 做任何前綴處理。
 *
 * <p><b>方向：</b>S2C（伺服器 → 客戶端）。本 mod 為伺服器端，僅<b>送出</b>此訊息；其
 * {@link #handle} 於伺服器端實質為 no-op（{@code setPacketHandled(true)} 即可），真正的消費者是
 * 客戶端 Mod（選項 A，獨立的 compile-only 模組，Phase 5）。客戶端對應實作為後續工作。
 */
public final class NonceMsg {

    /** 通道 id：{@code zerotrustauth:auth}。協定版本字串為 {@code "1"}。 */
    public static final ResourceLocation CHANNEL_ID =
            new ResourceLocation("zerotrustauth", "auth");

    /** 協定版本（雙端須一致；不符即拒絕連線握手）。 */
    private static final String PROTOCOL_VERSION = "1";

    /**
     * 通道（建立一次）。1.19.2 以 {@link NetworkRegistry#newSimpleChannel} 建立：
     * 名稱、版本供應器、用戶端可接受版本判定、伺服器可接受版本判定。
     */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CHANNEL_ID,
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    /** 承載的 Nonce（32 bytes；長度由核心 ChallengeManager 決定）。 */
    private final byte[] nonce;

    public NonceMsg(byte[] nonce) {
        this.nonce = Objects.requireNonNull(nonce, "nonce");
    }

    public byte[] nonce() {
        return nonce;
    }

    /**
     * 於 {@code FMLCommonSetupEvent} 呼叫一次：在通道上註冊本訊息型別（discriminator 0）。
     * encode 寫出長度前綴的 byte 陣列，decode 讀回，handler 於伺服器端為 no-op。
     */
    public static void register() {
        CHANNEL.registerMessage(
                0,
                NonceMsg.class,
                NonceMsg::encode,
                NonceMsg::decode,
                NonceMsg::handle);
    }

    /** 編碼：寫出長度前綴（VarInt）的 byte 陣列。 */
    public static void encode(NonceMsg msg, FriendlyByteBuf buf) {
        buf.writeByteArray(msg.nonce);
    }

    /** 解碼：讀回長度前綴的 byte 陣列。 */
    public static NonceMsg decode(FriendlyByteBuf buf) {
        return new NonceMsg(buf.readByteArray());
    }

    /**
     * 處理：本 mod 為伺服器端，僅送出此 S2C 訊息，故此處實質為 no-op——
     * 僅標記封包已處理。真正的消費者是客戶端 Mod（選項 A，獨立模組）。
     */
    public static void handle(NonceMsg msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
    }
}

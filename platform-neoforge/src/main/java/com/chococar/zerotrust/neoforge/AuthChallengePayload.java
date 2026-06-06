package com.chococar.zerotrust.neoforge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 選項 A 挑戰封包（計劃 3.1 / 5.2）：伺服器送出 32-byte Nonce 至客戶端 Mod，
 * 客戶端加領域前綴（{@code signature_domain + nonce}）後以 Ed25519 簽名回傳。
 *
 * <p>1.20.5+ 的 {@link CustomPacketPayload} API：每個 payload 需有唯一 {@link Type}
 * 與 {@link StreamCodec}。通道 id 固定為 {@code zerotrust:auth}（與 Paper 的 Plugin Message
 * 通道一致，便於跨平台客戶端共用）。
 *
 * <p><b>領域分隔（安全不變式 4）：</b>本封包僅承載「裸 Nonce」；領域前綴由<b>客戶端</b>於簽名前加上、
 * 由<b>核心</b>（{@code ZeroTrustCore} 經 {@code signature_domain}）以同一前綴驗證。伺服器端不在此處
 * 對 Nonce 做任何前綴處理。
 */
public record AuthChallengePayload(byte[] nonce) implements CustomPacketPayload {

    /** 通道 id：{@code zerotrust:auth}。 */
    public static final Identifier CHANNEL_ID =
            Identifier.fromNamespaceAndPath("zerotrust", "auth");

    /** payload 型別句柄（註冊與比對用）。 */
    public static final CustomPacketPayload.Type<AuthChallengePayload> TYPE =
            new CustomPacketPayload.Type<>(CHANNEL_ID);

    /**
     * 串流編解碼器：以長度前綴的 byte 陣列承載 Nonce。
     * {@link ByteBufCodecs#BYTE_ARRAY} 為 VarInt 長度前綴的 {@code byte[]} 編碼。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, AuthChallengePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BYTE_ARRAY, AuthChallengePayload::nonce,
                    AuthChallengePayload::new);

    @Override
    public CustomPacketPayload.Type<AuthChallengePayload> type() {
        return TYPE;
    }
}

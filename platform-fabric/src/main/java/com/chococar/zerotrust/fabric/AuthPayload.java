package com.chococar.zerotrust.fabric;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 選項 A 挑戰 / 回應的自訂封包（計劃 5.3：{@code ServerPlayNetworking}）。
 *
 * <p>後 1.20.5 的自訂封包 API（此處為 <b>Mojang 官方對應</b>名稱）：
 * 以 {@link CustomPacketPayload.Type} 與 {@link StreamCodec} 註冊，透過
 * {@code ServerPlayNetworking.send(player, payload)} 送出、
 * {@code registerGlobalReceiver(TYPE, ...)} 接收。
 *
 * <h2>通道與 wire 格式</h2>
 * 通道 ID 為 {@code zerotrust:auth}（與 Paper 的 Plugin Message 通道同名，計劃 5.2 / 5.3）。
 * 承載單一位元組陣列 {@code data}：
 * <ul>
 *   <li><b>S2C（挑戰）：</b>{@code data} = 32-byte Nonce（核心 {@code sendChallenge} 提供）。</li>
 *   <li><b>C2S（回應）：</b>{@code data} = {@code nonceLen(1 byte) || nonce || signature}。
 *       由（尚未實作的）客戶端 Mod 構造；本伺服器端接收時拆解後交給
 *       {@code core.onSignatureResponse(uuid, connectionId, nonce, signature)}。</li>
 * </ul>
 *
 * <p>為抵禦惡意超大封包，編解碼以 {@link ByteBufCodecs#byteArray(int)} 設上限
 * （{@link #MAX_PAYLOAD_BYTES}）。Ed25519 簽名 64 bytes + Nonce 32 bytes + 1 byte 長度
 * 遠小於此上限，正常流量不受影響。
 */
public record AuthPayload(byte[] data) implements CustomPacketPayload {

    /** 通道 ID（與 Paper {@code PaperPlatformAdapter.CHANNEL} = {@code zerotrust:auth} 一致）。 */
    public static final ResourceLocation CHANNEL_ID =
            ResourceLocation.fromNamespaceAndPath("zerotrust", "auth");

    /** {@link CustomPacketPayload} 型別識別（Mojang 對應：巢狀型別為 {@code Type}）。 */
    public static final CustomPacketPayload.Type<AuthPayload> TYPE =
            new CustomPacketPayload.Type<>(CHANNEL_ID);

    /** 承載上限（bytes）。遠大於合法回應（1 + 32 + 64 = 97），但能擋下惡意巨量封包。 */
    public static final int MAX_PAYLOAD_BYTES = 1024;

    /**
     * 位元組陣列編解碼。{@code map(decode, encode)}：解碼 {@code byte[] -> AuthPayload}，
     * 編碼 {@code AuthPayload -> byte[]}。底層 {@link ByteBufCodecs#byteArray(int)} 為
     * {@code StreamCodec<ByteBuf, byte[]>}，故此處型別為 {@code StreamCodec<ByteBuf, AuthPayload>}。
     * 因 {@code ByteBuf} 是 {@code RegistryFriendlyByteBuf} 的父型別，正好滿足
     * {@code PayloadTypeRegistry.register} 要求的 {@code StreamCodec<? super RegistryFriendlyByteBuf, T>}。
     */
    public static final StreamCodec<ByteBuf, AuthPayload> STREAM_CODEC =
            ByteBufCodecs.byteArray(MAX_PAYLOAD_BYTES).map(AuthPayload::new, AuthPayload::data);

    @Override
    public CustomPacketPayload.Type<AuthPayload> type() {
        return TYPE;
    }
}

package com.chococar.zerotrust.fabric;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 選項 A 挑戰 / 回應的自訂封包（計劃 5.3：{@code ServerPlayNetworking}）。
 *
 * <p>後 1.20.5 的 {@link CustomPayload} API：以 {@link Id} 與 {@link PacketCodec} 註冊，
 * 透過 {@code ServerPlayNetworking.send(player, payload)} 送出、
 * {@code registerGlobalReceiver(ID, ...)} 接收。
 *
 * <h2>通道與 wire 格式</h2>
 * 通道 ID 為 {@code zerotrust:auth}（與 Paper 的 Plugin Message 通道同名，計劃 5.2 / 5.3）。
 * 承載單一位元組陣列 {@code data}：
 * <ul>
 *   <li><b>S2C（挑戰）：</b>{@code data} = 32-byte Nonce（核心 {@code sendChallenge} 提供）。</li>
 *   <li><b>C2S（回應）：</b>{@code data} = {@code nonceLen(1 byte) || nonce || signature}。
 *       由（尚未實作的）客戶端 Mod 構造；本伺服器端在接收時拆解後交給
 *       {@code core.onSignatureResponse(uuid, connectionId, nonce, signature)}。</li>
 * </ul>
 *
 * <p>為抵禦惡意超大封包，C2S 解碼以 {@link PacketCodecs#byteArray(int)} 設上限
 * （{@link #MAX_PAYLOAD_BYTES}）。Ed25519 簽名 64 bytes + Nonce 32 bytes + 1 byte 長度
 * 遠小於此上限，正常流量不受影響。
 */
public record AuthPayload(byte[] data) implements CustomPayload {

    /** 通道 ID（與 Paper {@code PaperPlatformAdapter.CHANNEL} 一致）。 */
    public static final Identifier CHANNEL_ID = Identifier.of("zerotrust", "auth");

    /** {@link CustomPayload} 型別識別。 */
    public static final CustomPayload.Id<AuthPayload> ID = new CustomPayload.Id<>(CHANNEL_ID);

    /**
     * 承載上限（bytes）。遠大於合法回應（1 + 32 + 64 = 97），但能擋下惡意巨量封包。
     */
    public static final int MAX_PAYLOAD_BYTES = 1024;

    /**
     * 位元組陣列編解碼。{@code xmap(decode, encode)}：解碼 {@code byte[] -> AuthPayload}，
     * 編碼 {@code AuthPayload -> byte[]}。底層 {@link PacketCodecs#byteArray(int)} 為
     * {@code PacketCodec<ByteBuf, byte[]>}，故此處型別為 {@code PacketCodec<ByteBuf, AuthPayload>}。
     * 因 {@code ByteBuf} 是 {@code RegistryByteBuf} 的父型別，正好滿足
     * {@code PayloadTypeRegistry.register} 要求的 {@code PacketCodec<? super RegistryByteBuf, T>}。
     */
    public static final PacketCodec<ByteBuf, AuthPayload> CODEC =
            PacketCodecs.byteArray(MAX_PAYLOAD_BYTES)
                    .xmap(AuthPayload::new, AuthPayload::data);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}

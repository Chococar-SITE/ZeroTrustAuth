package com.chococar.zerotrust.fabric;

import com.chococar.zerotrust.auth.AuthEngine;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 連線生命週期與選項 A 回應接收（計劃 3.4 / 5.3）。名稱採用 <b>Mojang 官方對應</b>。
 *
 * <ul>
 *   <li><b>JOIN：</b>偵測管理員帳號 → 產生新連線 ID（綁定 Nonce 至此連線，計劃 3.2 / 3.5）
 *       → {@code engine.onAdminJoin(...)}（引擎凍結、剝奪原版 OP、送提示並啟動驗證）。</li>
 *   <li><b>DISCONNECT：</b>{@code engine.onAdminQuit(...)} 立即撤權並清理。</li>
 *   <li><b>C2S 回應：</b>{@link AuthPayload} 接收後拆解為 {@code nonce}/{@code signature}，
 *       交給 {@code engine.onSignatureResponse(...)}。此回呼於伺服器主執行緒執行（Fabric 保證）。</li>
 * </ul>
 *
 * <p>一般玩家零感知：非管理員帳號完全不觸發任何凍結 / 驗證邏輯。
 */
final class ConnectionListener {

    private final AuthEngine engine;
    private final FabricPlatformAdapter adapter;
    /** 每次登入的連線 ID（與指令層共享，供 verify 使用）。 */
    private final Map<UUID, String> connectionIds;

    ConnectionListener(AuthEngine engine, FabricPlatformAdapter adapter, Map<UUID, String> connectionIds) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.connectionIds = Objects.requireNonNull(connectionIds, "connectionIds");
    }

    /** 註冊 JOIN / DISCONNECT 與 C2S 回應接收器。 */
    void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            if (player == null) {
                return;
            }
            UUID uuid = player.getUUID();
            if (!adapter.isAdminAccount(uuid)) {
                return; // 一般玩家零感知。
            }
            // 每次登入產生新的連線 ID，將 Nonce 綁定至此連線（計劃 3.2 / 3.5）。
            String connectionId = UUID.randomUUID().toString();
            connectionIds.put(uuid, connectionId);
            // 引擎會凍結、剝奪原版 OP、送出凍結提示並啟動驗證；此處不重複送提示。
            engine.onAdminJoin(uuid, player.getGameProfile().name(), connectionId);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            if (player == null) {
                return;
            }
            UUID uuid = player.getUUID();
            connectionIds.remove(uuid);
            if (adapter.isAdminAccount(uuid)) {
                engine.onAdminQuit(uuid);
            }
        });

        // 選項 A：客戶端簽名回應。封包必須先註冊（見 ZeroTrustFabric），再註冊接收器。
        ServerPlayNetworking.registerGlobalReceiver(AuthPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) {
                return;
            }
            UUID uuid = player.getUUID();
            if (!adapter.isAdminAccount(uuid)) {
                return;
            }
            String connectionId = connectionIds.get(uuid);
            byte[] data = payload.data();
            byte[][] split = splitResponse(data);
            if (split == null) {
                // 畸形回應：交給引擎計入失敗（傳空簽名，必然驗證失敗）。
                engine.onSignatureResponse(uuid, connectionId, new byte[0], new byte[0]);
                return;
            }
            engine.onSignatureResponse(uuid, connectionId, split[0], split[1]);
        });
    }

    /**
     * 拆解 C2S 回應 {@code nonceLen(1 byte) || nonce || signature}。
     *
     * @return {@code [nonce, signature]}；格式不符回傳 {@code null}。
     */
    static byte[][] splitResponse(byte[] data) {
        if (data == null || data.length < 1) {
            return null;
        }
        int nonceLen = data[0] & 0xFF;
        // 至少要有長度位元組 + nonce；簽名可為其餘（長度交由核心 Ed25519 驗證把關）。
        if (nonceLen <= 0 || 1 + nonceLen > data.length) {
            return null;
        }
        byte[] nonce = Arrays.copyOfRange(data, 1, 1 + nonceLen);
        byte[] signature = Arrays.copyOfRange(data, 1 + nonceLen, data.length);
        return new byte[][] {nonce, signature};
    }
}

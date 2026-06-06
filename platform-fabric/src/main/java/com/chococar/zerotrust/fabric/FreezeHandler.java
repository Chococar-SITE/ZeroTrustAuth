package com.chococar.zerotrust.fabric;

import com.chococar.zerotrust.auth.AuthEngine;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;

/**
 * 強制執行「凍結」隔離艙（計劃 4.5 / 5.3）。凍結期間管理員對世界與其他玩家應為零影響，
 * 僅放行 {@code /authkey}。名稱採用 <b>Mojang 官方對應</b>。
 *
 * <h2>凍結手段</h2>
 * <ul>
 *   <li><b>移動：</b>{@code ServerTickEvents.END_SERVER_TICK} 每 tick 將凍結中的管理員拉回
 *       凍結時捕捉的座標（{@link FabricPlatformAdapter#frozenPosition}），並歸零速度；
 *       <b>保留視角</b>（不改 yaw/pitch），達成「僅可轉視角、不可位移」。同時重申無敵。</li>
 *   <li><b>方塊 / 實體互動：</b>{@link UseBlockCallback}、{@link AttackBlockCallback}、
 *       {@link PlayerBlockBreakEvents#BEFORE}、{@link UseEntityCallback}、{@link AttackEntityCallback}
 *       於凍結中一律取消（伺服器側）。</li>
 *   <li><b>聊天：</b>{@link ServerMessageEvents#ALLOW_CHAT_MESSAGE} 於凍結中拒絕（防資訊洩漏 / 社交工程）。</li>
 * </ul>
 *
 * <p>每個被取消的非驗證互動皆餵 {@link AuthEngine#onFrozenPacket}（速率限制，計劃 6.4）；
 * 若引擎回報超限，引擎自身會踢出並警報。
 *
 * <h2>限制（已記錄之 follow-up）</h2>
 * <ul>
 *   <li>背包 / 容器點擊：Fabric 無對應的伺服器側「容器點擊」公開事件可在不寫 Mixin 下攔截；
 *       本系統改以「移除原版 OP + 不授予本系統權限」降低風險，且凍結中無法移動至容器互動距離外。
 *       完整封鎖（含 GUI 點擊）為 Mixin 範疇的 follow-up。</li>
 *   <li>任意指令攔截：Fabric 無 {@code PlayerCommandPreprocessEvent} 對等事件；本系統僅註冊
 *       {@code /authkey}，其餘特權指令因已被剝奪 OP 且未獲本系統授權而應失敗。完整「凍結期僅放行
 *       /authkey」需 Mixin 介入 command dispatch，列為 follow-up。</li>
 *   <li>物品使用（{@code UseItemCallback}）：未攔截；可逃脫之傳送類（終界珍珠 / 紫頋果）已由每 tick
 *       位置鎖拉回，放置流體 / 方塊類則經 {@link UseBlockCallback} 攔截。</li>
 * </ul>
 */
final class FreezeHandler {

    private final AuthEngine engine;
    private final FabricPlatformAdapter adapter;

    FreezeHandler(AuthEngine engine, FabricPlatformAdapter adapter) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    // ── 每 tick 位置鎖 ───────────────────────────────────────

    /** 由 {@code ServerTickEvents.END_SERVER_TICK} 呼叫（已在伺服器主執行緒）。 */
    void onEndServerTick(net.minecraft.server.MinecraftServer server) {
        // 僅在有凍結對象時迭代玩家清單。
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID uuid = p.getUUID();
            if (!adapter.isFrozen(uuid)) {
                continue;
            }
            FabricPlatformAdapter.FrozenPosition pos = adapter.frozenPosition(uuid);
            if (pos == null) {
                continue;
            }
            // 重申無敵（縱深防禦）。
            if (!p.isInvulnerable()) {
                p.setInvulnerable(true);
            }
            // 歸零速度，避免累積位移 / 掉落。
            p.setDeltaMovement(Vec3.ZERO);
            p.fallDistance = 0f;
            // 若離開捕捉座標（超過極小容差），拉回原座標但保留當前視角。
            double dx = p.getX() - pos.x();
            double dy = p.getY() - pos.y();
            double dz = p.getZ() - pos.z();
            if ((dx * dx + dy * dy + dz * dz) > 1.0e-6) {
                // 以連線 teleport 將座標校正回客戶端，並帶入「當前」yaw/pitch →
                // 僅鎖位置，視角仍可自由轉動（Mojang 對應：
                // ServerGamePacketListenerImpl.teleport(x, y, z, yRot, xRot)）。
                p.connection.teleport(pos.x(), pos.y(), pos.z(), p.getYRot(), p.getXRot());
                p.setDeltaMovement(Vec3.ZERO);
                p.hurtMarked = true;
                engine.onFrozenPacket(uuid);
            }
        }
    }

    // ── 互動攔截（凍結中取消）────────────────────────────────

    /** 註冊所有互動 / 聊天攔截。 */
    void registerInteractionGuards() {
        // 右鍵方塊（使用）。
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
                blocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);

        // 左鍵方塊（攻擊 / 開始破壞）。
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                blocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);

        // 右鍵實體（使用）。
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                blocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);

        // 左鍵實體（攻擊）。
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                blocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);

        // 破壞方塊（伺服器側；回傳 false 取消）。
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                !blocked(player));

        // 聊天（伺服器側；回傳 false 取消）。
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            UUID uuid = sender.getUUID();
            if (adapter.isFrozen(uuid)) {
                engine.onFrozenPacket(uuid);
                return false;
            }
            return true;
        });
    }

    /**
     * 該玩家是否為「凍結中且應攔截其互動」。攔截時餵速率限制器。
     * 注意：互動事件於客戶端與伺服器皆會觸發；此處只關心伺服器側（{@code !level.isClientSide}）。
     */
    private boolean blocked(Player player) {
        if (player == null || player.level().isClientSide()) {
            return false;
        }
        UUID uuid = player.getUUID();
        if (adapter.isFrozen(uuid)) {
            engine.onFrozenPacket(uuid);
            return true;
        }
        return false;
    }
}

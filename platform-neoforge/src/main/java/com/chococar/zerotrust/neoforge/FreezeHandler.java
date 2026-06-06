package com.chococar.zerotrust.neoforge;

import com.chococar.zerotrust.auth.AuthEngine;

import com.mojang.brigadier.ParseResults;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Objects;
import java.util.UUID;

/**
 * 強制執行「凍結」隔離艙（計劃 4.5）。凍結期間玩家對世界與其他玩家應為零影響，
 * 僅放行 {@code /authkey}。註冊於 {@code NeoForge.EVENT_BUS}（遊戲事件匯流排）。
 *
 * <h2>攔截面（凍結中）</h2>
 * <ul>
 *   <li><b>移動</b>：{@link ServerTickEvent.Post} 每 tick 把位置回拉至凍結點（保留視角）。
 *       NeoForge 伺服器端無乾淨的「玩家移動」事件，故以 tick 回拉為準（文件化做法）。</li>
 *   <li><b>無敵</b>：{@link LivingIncomingDamageEvent} 取消傷害（防死亡 / 掉落）。</li>
 *   <li><b>方塊 / 互動</b>：{@link BlockEvent.BreakEvent}、{@link BlockEvent.EntityPlaceEvent}、
 *       {@link PlayerInteractEvent} 各子型別取消。</li>
 *   <li><b>聊天</b>：{@link ServerChatEvent} 取消。</li>
 *   <li><b>指令</b>：{@link CommandEvent} 僅放行 {@code /authkey}，其餘取消。</li>
 * </ul>
 *
 * <p>每個被取消的非驗證互動皆餵 {@link AuthEngine#onFrozenPacket}（凍結期封包速率限制，計劃 6.4）；
 * 若引擎回報超限，引擎自身會踢出並警報。
 *
 * <p>背包 / 容器：凍結期間 {@link PlayerInteractEvent} 已擋下開啟方塊容器的右鍵；NeoForge 無單一
 * 「開啟容器」可取消事件，故倚賴互動攔截 + 無敵 + 位置鎖定構成隔離艙。
 */
final class FreezeHandler {

    private final AuthEngine engine;
    private final NeoForgePlatformAdapter adapter;
    private final MinecraftServer server;

    FreezeHandler(AuthEngine engine, NeoForgePlatformAdapter adapter, MinecraftServer server) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.server = Objects.requireNonNull(server, "server");
    }

    // ── 移動：每 tick 回拉至凍結點（僅保留視角）──────────────

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        // 走訪所有線上玩家，僅處理凍結中者（一般玩家零感知）。
        // 直接持有 server 參考，避免依賴 tick 事件的存取器命名差異。
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID uuid = p.getUUID();
            if (!adapter.isFrozen(uuid)) {
                continue;
            }
            // 持續無敵（防競態下被取消）。
            if (!p.isInvulnerable()) {
                p.setInvulnerable(true);
            }
            Vec3 anchor = adapter.frozenAnchor(uuid);
            if (anchor == null) {
                continue;
            }
            // 僅在 block 座標改變時回拉（允許同格內視角轉動 / 微幅滑動）。
            if (Math.floor(p.getX()) != Math.floor(anchor.x)
                    || Math.floor(p.getY()) != Math.floor(anchor.y)
                    || Math.floor(p.getZ()) != Math.floor(anchor.z)) {
                // 透過連線層回拉並同步至客戶端；保留目前視角（yaw/pitch）。
                p.setDeltaMovement(Vec3.ZERO);
                p.connection.teleport(anchor.x, anchor.y, anchor.z, p.getYRot(), p.getXRot());
                engine.onFrozenPacket(uuid);
            }
        }
    }

    // ── 指令：僅放行 /authkey ───────────────────────────────

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        ParseResults<CommandSourceStack> results = event.getParseResults();
        ServerPlayer player = results.getContext().getSource().getPlayer();
        if (player == null) {
            return; // 主控台 / 非玩家來源不受凍結限制。
        }
        UUID uuid = player.getUUID();
        if (!adapter.isFrozen(uuid)) {
            return;
        }
        String input = results.getReader().getString();
        if (!isAuthCommand(input)) {
            event.setCanceled(true);
            engine.onFrozenPacket(uuid);
        }
    }

    /** 僅放行 {@code /authkey}（含子指令）；其餘一律攔截。輸入可能不含前導斜線。 */
    private static boolean isAuthCommand(String raw) {
        if (raw == null) {
            return false;
        }
        String m = raw.trim().toLowerCase();
        if (m.startsWith("/")) {
            m = m.substring(1);
        }
        return m.equals("authkey") || m.startsWith("authkey ");
    }

    // ── 傷害：無敵（防死亡 / 掉落）──────────────────────────

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Player player && adapter.isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    // ── 方塊 ────────────────────────────────────────────────

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player != null && adapter.isFrozen(player.getUUID())) {
            event.setCanceled(true);
            engine.onFrozenPacket(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && adapter.isFrozen(player.getUUID())) {
            event.setCanceled(true);
            engine.onFrozenPacket(player.getUUID());
        }
    }

    // ── 互動（含開啟容器右鍵、使用物品、左鍵）──────────────

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        cancelInteract(event, event.getEntity());
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        cancelInteract(event, event.getEntity());
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        cancelInteract(event, event.getEntity());
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        cancelInteract(event, event.getEntity());
    }

    @SubscribeEvent
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        cancelInteract(event, event.getEntity());
    }

    private void cancelInteract(PlayerInteractEvent event, Player player) {
        if (player != null && adapter.isFrozen(player.getUUID())) {
            // setCanceled(true) 已完整取消該次互動（含開啟容器、使用物品等），足夠構成隔離艙。
            event.setCanceled(true);
            engine.onFrozenPacket(player.getUUID());
        }
    }

    // ── 聊天：禁止（防資訊洩漏 / 社交工程）──────────────────

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player != null && adapter.isFrozen(player.getUUID())) {
            event.setCanceled(true);
            engine.onFrozenPacket(player.getUUID());
        }
    }
}

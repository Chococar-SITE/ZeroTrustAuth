package com.chococar.zerotrust.paper;

import com.chococar.zerotrust.auth.AuthEngine;

import io.papermc.paper.event.player.AsyncChatEvent;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 強制執行「凍結」隔離艙（計劃 4.5）。凍結期間玩家對世界與其他玩家應為零影響，
 * 僅放行 {@code /authkey}。
 *
 * <h2>凍結觸發點與 TOCTOU</h2>
 * 本 MVP 在 {@link PlayerJoinEvent} 觸發 {@link AuthEngine#onAdminJoin}，並在更早的
 * 連線階段之前即以 {@link PaperPlatformAdapter#isFrozen} 判斷攔截。<b>注意：</b>計劃 4.5 建議在
 * 最早 hook（{@code AsyncPlayerPreLoginEvent} / {@code PlayerLoginEvent}）標記凍結以消除約 1 tick
 * 的 TOCTOU 空窗。JoinEvent 對 MVP 可接受，但仍有極短空窗；待客戶端封包協定就緒後應前移標記點。
 *
 * <p>所有「凍結中」攔截皆以 {@link PaperPlatformAdapter#isFrozen} 為準（執行緒安全的共享集合）。
 * 對每個被取消的非驗證互動，餵 {@link AuthEngine#onFrozenPacket} 給速率限制器（計劃 6.4）；
 * 若引擎回報超限，引擎自身會踢出並警報。
 */
final class FreezeListener implements Listener {

    private final AuthEngine engine;
    private final PaperPlatformAdapter adapter;
    /** 每次登入的連線 ID（與指令層共享，供 verify 使用）。 */
    private final Map<UUID, String> connectionIds;

    FreezeListener(AuthEngine engine, PaperPlatformAdapter adapter, Map<UUID, String> connectionIds) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.connectionIds = Objects.requireNonNull(connectionIds, "connectionIds");
    }

    // ── 連線生命週期 ─────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!adapter.isAdminAccount(uuid)) {
            return; // 一般玩家零感知。
        }
        // 每次登入產生新的連線 ID，將 Nonce 綁定至此連線（計劃 3.2 / 3.5）。
        String connectionId = UUID.randomUUID().toString();
        connectionIds.put(uuid, connectionId);
        // 引擎會凍結、剝奪原版 OP、送出凍結提示（MSG_FROZEN_PROMPT）並啟動驗證；
        // 故此處不重複送提示，避免玩家收到兩次相同訊息。
        engine.onAdminJoin(uuid, player.getName(), connectionId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        connectionIds.remove(uuid);
        if (adapter.isAdminAccount(uuid)) {
            engine.onAdminQuit(uuid);
        }
    }

    // ── 指令攔截（僅放行 /authkey）────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (!adapter.isFrozen(uuid)) {
            return;
        }
        String msg = e.getMessage();
        if (!isAuthCommand(msg)) {
            e.setCancelled(true);
            engine.onFrozenPacket(uuid);
        }
    }

    /** 僅放行 {@code /authkey}（含其子指令）；其餘一律攔截。 */
    private static boolean isAuthCommand(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase();
        return m.equals("/authkey") || m.startsWith("/authkey ");
    }

    // ── 移動：取消位移，允許視角轉動 ──────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (!adapter.isFrozen(uuid)) {
            return;
        }
        if (e.getTo() == null) {
            return;
        }
        // 僅在 block 座標改變時取消（允許同格內的視角轉動 / 微幅滑動）。
        if (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockY() != e.getTo().getBlockY()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
            // 保留視角，僅把座標鎖回原 block 中心高度的來源位置。
            e.setTo(e.getFrom().clone().setDirection(e.getTo().getDirection()));
            engine.onFrozenPacket(uuid);
        }
    }

    // ── 背包 / 容器 ──────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        UUID uuid = e.getWhoClicked().getUniqueId();
        if (adapter.isFrozen(uuid)) {
            e.setCancelled(true);
            engine.onFrozenPacket(uuid);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (adapter.isFrozen(uuid)) {
            e.setCancelled(true);
            engine.onFrozenPacket(uuid);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (adapter.isFrozen(uuid)) {
            e.setCancelled(true);
            engine.onFrozenPacket(uuid);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickupItem(PlayerAttemptPickupItemEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (adapter.isFrozen(uuid)) {
            e.setCancelled(true);
            // 撿物不算主動攻擊封包，不計入速率（避免被動環境誤觸）。
        }
    }

    // ── 方塊互動 ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (adapter.isFrozen(uuid)) {
            e.setCancelled(true);
            engine.onFrozenPacket(uuid);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (adapter.isFrozen(uuid)) {
            e.setCancelled(true);
            engine.onFrozenPacket(uuid);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (adapter.isFrozen(uuid)) {
            e.setCancelled(true);
            engine.onFrozenPacket(uuid);
        }
    }

    // ── 受傷：無敵（防死亡 / 掉落）──────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player player)) {
            return;
        }
        if (adapter.isFrozen(player.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    // ── 聊天：禁止（防資訊洩漏 / 社交工程）──────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (adapter.isFrozen(uuid)) {
            e.setCancelled(true);
            engine.onFrozenPacket(uuid);
        }
    }
}

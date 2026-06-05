package com.chococar.zerotrust.paper;

import com.chococar.zerotrust.platform.PlatformAdapter;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Paper / Spigot 的 {@link PlatformAdapter} 實作（計劃 2.3 / 4.3 / 4.5 / 5.2）。
 *
 * <h2>執行緒模型</h2>
 * 引擎可能從任意執行緒（如 Discord 回呼、排程器）呼叫本介面。所有對 Bukkit 世界 / 玩家 /
 * 權限的變更<b>必須</b>在主執行緒進行，故凡有副作用的操作皆透過
 * {@link #onMain(Runnable)} 排程到主執行緒。
 *
 * <h2>權限（計劃 5.2 — 不可持久化）</h2>
 * <ul>
 *   <li>有 LuckPerms：僅用 <b>transient</b> node（{@code user.transientData().add(...)}）授予
 *       設定的權限 node，並 {@code saveUser}（transient 不入庫，只觸發即時重算）。</li>
 *   <li>無 LuckPerms：以 Bukkit {@link PermissionAttachment} 授予（attachment 天生非持久化，
 *       重啟即失效），存於本物件的 map。</li>
 * </ul>
 * 兩種路徑皆在 {@link #revokeAdminPerm}、{@link ZeroTrustPlugin#onDisable()} 被主動撤回（fail-closed）。
 *
 * <h2>原版 OP（計劃 4.3）</h2>
 * {@link #stripVanillaOp} 同時清除線上玩家與 {@code ops.json} 的 OP；{@link #restoreVanillaOp}
 * 預設為 no-op（理想：管理員完全不用原版 OP，權限全由本系統 transient 授予）。
 */
final class PaperPlatformAdapter implements PlatformAdapter {

    /** 選項 A 挑戰封包使用的 Plugin Message 通道（計劃 5.2）。 */
    static final String CHANNEL = "zerotrust:auth";

    private final Plugin plugin;
    private final Logger log;
    private final Set<UUID> adminAccounts;
    private final String adminPermissionNode;

    /** 目前被凍結的玩家（執行緒安全；監聽器據此攔截操作）。 */
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    /** 凍結前的行走速度，解凍時還原（key 不在表示未保存）。 */
    private final Map<UUID, Float> savedWalkSpeed = new ConcurrentHashMap<>();
    /** 無 LuckPerms 時持有的權限 attachment。 */
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    /** 是否使用 LuckPerms（啟動時判定一次；不可用則退回 attachment）。 */
    private final boolean luckPermsPresent;

    PaperPlatformAdapter(Plugin plugin, Set<UUID> adminAccounts, String adminPermissionNode) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.log = plugin.getLogger();
        this.adminAccounts = Set.copyOf(adminAccounts);
        this.adminPermissionNode = Objects.requireNonNull(adminPermissionNode, "adminPermissionNode");
        this.luckPermsPresent = detectLuckPerms();
    }

    // ── 凍結狀態（供監聽器查詢）─────────────────────────────

    boolean isFrozen(UUID uuid) {
        return uuid != null && frozen.contains(uuid);
    }

    String adminPermissionNode() {
        return adminPermissionNode;
    }

    boolean usingLuckPerms() {
        return luckPermsPresent;
    }

    // ── PlatformAdapter ─────────────────────────────────────

    @Override
    public void freezePlayer(UUID uuid) {
        if (uuid == null) {
            return;
        }
        frozen.add(uuid);
        onMain(() -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                return;
            }
            // 無敵：防凍結期間死亡 / 掉落（計劃 4.5）。EntityDamageEvent 亦會被監聽器取消，雙保險。
            p.setInvulnerable(true);
            // 停止位移傾向：保存並清零行走速度（視角仍可轉動；位移由 PlayerMoveEvent 取消）。
            if (!savedWalkSpeed.containsKey(uuid)) {
                savedWalkSpeed.put(uuid, p.getWalkSpeed());
            }
            p.setWalkSpeed(0f);
        });
    }

    @Override
    public void unfreezePlayer(UUID uuid) {
        if (uuid == null) {
            return;
        }
        frozen.remove(uuid);
        Float restore = savedWalkSpeed.remove(uuid);
        onMain(() -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                return;
            }
            p.setInvulnerable(false);
            p.setWalkSpeed(restore != null ? restore : 0.2f); // 0.2f 為原版預設行走速度
        });
    }

    @Override
    public void grantAdminPerm(UUID uuid) {
        if (uuid == null) {
            return;
        }
        onMain(() -> {
            if (luckPermsPresent) {
                try {
                    LuckPermsBridge.grant(uuid, adminPermissionNode);
                    return;
                } catch (Throwable t) {
                    // LuckPerms 路徑失敗：退回 attachment，並記錄（不可 fail-open，但有後備授權路徑）。
                    log.warning("LuckPerms 授權失敗，改用 Bukkit attachment：" + t.getMessage());
                }
            }
            grantViaAttachment(uuid);
        });
    }

    @Override
    public void revokeAdminPerm(UUID uuid) {
        if (uuid == null) {
            return;
        }
        onMain(() -> {
            // 兩條路徑都嘗試撤回，確保不殘留（fail-closed）。
            if (luckPermsPresent) {
                try {
                    LuckPermsBridge.revoke(uuid, adminPermissionNode);
                } catch (Throwable t) {
                    log.warning("LuckPerms 撤權失敗：" + t.getMessage());
                }
            }
            revokeAttachment(uuid);
        });
    }

    @Override
    public void kickPlayer(UUID uuid, String reason) {
        if (uuid == null) {
            return;
        }
        final String msg = reason == null ? "" : reason;
        onMain(() -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.kickPlayer(msg);
            }
        });
    }

    @Override
    public void sendMessage(UUID uuid, String message) {
        if (uuid == null || message == null) {
            return;
        }
        onMain(() -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(message);
            }
        });
    }

    @Override
    public boolean isAdminAccount(UUID uuid) {
        return uuid != null && adminAccounts.contains(uuid);
    }

    @Override
    public void stripVanillaOp(UUID uuid) {
        if (uuid == null) {
            return;
        }
        onMain(() -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOp()) {
                online.setOp(false);
            }
            // 同時清 ops.json（離線時也生效；計劃 4.3）。
            OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
            if (off.isOp()) {
                off.setOp(false);
            }
        });
    }

    @Override
    public void restoreVanillaOp(UUID uuid) {
        // 預設 no-op：理想是管理員完全不依賴原版 OP，權限全由本系統 transient 授予（計劃 4.3）。
        // 若部署環境確實需要原版 OP，可在此 setOp(true)，但會擴大繞過面，不建議。
    }

    @Override
    public void sendChallenge(UUID uuid, byte[] nonce) {
        if (uuid == null || nonce == null) {
            return;
        }
        final byte[] payload = nonce.clone();
        onMain(() -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                return;
            }
            try {
                // 盡力而為：目前尚無客戶端 Mod，無人回應亦無妨（逾時後走選項 B / 嚴格模式）。
                p.sendPluginMessage(plugin, CHANNEL, payload);
            } catch (RuntimeException e) {
                // 通道未註冊或其他狀況不可拖垮驗證流程。
                log.fine("送出選項 A 挑戰失敗：" + e.getMessage());
            }
        });
    }

    @Override
    public boolean isOnline(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Player p = Bukkit.getPlayer(uuid);
        return p != null && p.isOnline();
    }

    @Override
    public Optional<String> getPlayerName(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return Optional.of(online.getName());
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return Optional.ofNullable(name);
    }

    @Override
    public Optional<String> getPlayerIp(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) {
            return Optional.empty();
        }
        java.net.InetSocketAddress addr = p.getAddress();
        if (addr == null || addr.getAddress() == null) {
            return Optional.empty();
        }
        // 僅回傳 IP 字串；雜湊由核心 AuditLog 以 HMAC-SHA256＋密鑰鹽處理（計劃 6.2）。
        return Optional.of(addr.getAddress().getHostAddress());
    }

    // ── 生命週期清理（由 onDisable 呼叫）────────────────────

    /**
     * 撤回所有 attachment 並嘗試撤回所有 LuckPerms transient 授權，清空凍結狀態。
     * 於 {@code onDisable} 呼叫（fail-closed，計劃 5.2）。本方法在主執行緒同步執行。
     */
    void revokeAllAndClear() {
        // 清 LuckPerms transient（針對所有目前在線的管理員帳號）。
        if (luckPermsPresent) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                if (adminAccounts.contains(id)) {
                    try {
                        LuckPermsBridge.revoke(id, adminPermissionNode);
                    } catch (Throwable t) {
                        log.warning("onDisable 撤回 LuckPerms 授權失敗：" + t.getMessage());
                    }
                }
            }
        }
        // 移除所有 attachment。
        for (UUID id : Set.copyOf(attachments.keySet())) {
            revokeAttachment(id);
        }
        attachments.clear();
        frozen.clear();
        savedWalkSpeed.clear();
    }

    // ── 內部：Bukkit attachment 後備 ────────────────────────

    private void grantViaAttachment(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) {
            return;
        }
        // 先移除舊的，避免重複疊加。
        revokeAttachment(uuid);
        PermissionAttachment att = p.addAttachment(plugin);
        att.setPermission(adminPermissionNode, true);
        attachments.put(uuid, att);
        p.recalculatePermissions();
    }

    private void revokeAttachment(UUID uuid) {
        PermissionAttachment att = attachments.remove(uuid);
        if (att == null) {
            return;
        }
        try {
            att.remove();
        } catch (IllegalArgumentException ignored) {
            // attachment 可能已隨玩家登出被移除。
        }
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            p.recalculatePermissions();
        }
    }

    // ── 內部：主執行緒排程 ──────────────────────────────────

    private void onMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            safeRun(r);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> safeRun(r));
        }
    }

    private void safeRun(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            log.severe("ZeroTrust 平台操作拋出例外：" + t);
        }
    }

    private boolean detectLuckPerms() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            return false;
        }
        try {
            // 觸發 provider 與 API 類別載入；若 API 不在 classpath 會丟 NoClassDefFoundError。
            return LuckPermsBridge.isReady();
        } catch (Throwable t) {
            log.warning("偵測到 LuckPerms 外掛但 API 無法使用，改用 Bukkit attachment：" + t.getMessage());
            return false;
        }
    }
}

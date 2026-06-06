package com.chococar.zerotrust.forge;

import com.chococar.zerotrust.platform.PlatformAdapter;

import com.mojang.authlib.GameProfile;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.net.SocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * MinecraftForge（伺服器端）的 {@link PlatformAdapter} 實作（計劃 2.3 / 4.3 / 4.5 / 5.2）。
 *
 * <h2>執行緒模型</h2>
 * 引擎可能從任意執行緒（Discord 回呼、排程器計時緒）呼叫；凡觸及伺服器 / 玩家 / 權限的副作用
 * 一律透過 {@link #onMain(Runnable)} 排程至伺服器主執行緒（{@link MinecraftServer#execute}）。
 * 純查詢（凍結集合、admin 集合）為執行緒安全的記憶體存取，不需上主執行緒。
 *
 * <h2>權限（安全不變式 2 — 不可持久化）</h2>
 * 以<b>純記憶體</b>的 {@link #granted} 集合作為唯一真實來源，由本 mod 自行強制（{@link #isAdminGranted}）。
 * 集合僅存記憶體：登出 / 到期 / 撤銷 / 伺服器停止皆主動清除，<b>絕不</b>寫入任何持久化權限儲存
 * （Forge 無內建持久化權限後端；此實作天生非持久化）。
 *
 * <p><b>後續整合（文件化）：</b>可額外註冊 Forge {@code PermissionNode}（{@code zerotrust.admin}），
 * 其 resolver 讀本集合，使其他 mod / 指令能以 {@code PermissionAPI.getPermission} 查得。此處刻意不註冊
 * 節點以縮小 API 面（不同 Forge 版本的 {@code PermissionGatherEvent.Nodes} 註冊方法名有差異），
 * 待客戶端 Mod 與權限整合需求明確後再加；不影響本里程碑（編譯 + build 產 jar）。
 *
 * <h2>原版 OP（安全不變式 3）</h2>
 * {@link #stripVanillaOp} 以 {@link PlayerList#deop(GameProfile)} 同步移除 {@code ops.json} 條目，
 * 防止管理員以原版 OP 繞過本系統。{@link #restoreVanillaOp} 預設 no-op（理想：完全不用原版 OP）。
 *
 * <h2>凍結（安全不變式 / 計劃 4.5）</h2>
 * 凍結僅維護集合與「凍結點座標」；實際位移回拉、互動 / 方塊 / 聊天 / 指令攔截由
 * {@link FreezeHandler}（事件）與伺服器 tick 執行。
 */
final class ForgePlatformAdapter implements PlatformAdapter {

    private final MinecraftServer server;
    private final Logger log;
    private final Set<UUID> adminAccounts;

    /** 目前被凍結的玩家（執行緒安全；{@link FreezeHandler} 據此攔截）。 */
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    /** 凍結時擷取的座標：tick 時把玩家位置回拉至此（僅保留視角）。 */
    private final Map<UUID, Vec3> frozenAnchor = new ConcurrentHashMap<>();

    /** 本適配維護的權限授予集合（純記憶體 / transient，唯一真實來源）。 */
    private final Set<UUID> granted = ConcurrentHashMap.newKeySet();

    ForgePlatformAdapter(MinecraftServer server, Set<UUID> adminAccounts, Logger log) {
        this.server = Objects.requireNonNull(server, "server");
        this.adminAccounts = Set.copyOf(Objects.requireNonNull(adminAccounts, "adminAccounts"));
        this.log = Objects.requireNonNull(log, "log");
    }

    // ── 供 FreezeHandler 查詢 ───────────────────────────────

    boolean isFrozen(UUID uuid) {
        return uuid != null && frozen.contains(uuid);
    }

    /** 凍結點座標（tick 回拉用）；未凍結回 {@code null}。 */
    Vec3 frozenAnchor(UUID uuid) {
        return uuid == null ? null : frozenAnchor.get(uuid);
    }

    /** 此 UUID 是否目前持有（transient）管理員授權。供 mod 內部其他元件查詢。 */
    boolean isAdminGranted(UUID uuid) {
        return uuid != null && granted.contains(uuid);
    }

    // ── PlatformAdapter ─────────────────────────────────────

    @Override
    public void freezePlayer(UUID uuid) {
        if (uuid == null) {
            return;
        }
        frozen.add(uuid);
        onMain(() -> {
            ServerPlayer p = player(uuid);
            if (p == null) {
                return;
            }
            // 擷取凍結點（tick 回拉的基準）。
            frozenAnchor.put(uuid, p.position());
            // 無敵：防凍結期間死亡 / 掉落（計劃 4.5）。傷害事件亦會被取消，雙保險。
            p.setInvulnerable(true);
        });
    }

    @Override
    public void unfreezePlayer(UUID uuid) {
        if (uuid == null) {
            return;
        }
        frozen.remove(uuid);
        frozenAnchor.remove(uuid);
        onMain(() -> {
            ServerPlayer p = player(uuid);
            if (p == null) {
                return;
            }
            p.setInvulnerable(false);
        });
    }

    @Override
    public void grantAdminPerm(UUID uuid) {
        if (uuid == null) {
            return;
        }
        // 純記憶體授予（transient）；resolver 立即反映。在主執行緒更新以與其他狀態變更同序。
        onMain(() -> granted.add(uuid));
    }

    @Override
    public void revokeAdminPerm(UUID uuid) {
        if (uuid == null) {
            return;
        }
        onMain(() -> granted.remove(uuid));
    }

    @Override
    public void kickPlayer(UUID uuid, String reason) {
        if (uuid == null) {
            return;
        }
        final String msg = reason == null ? "" : reason;
        onMain(() -> {
            ServerPlayer p = player(uuid);
            if (p != null) {
                p.connection.disconnect(Component.literal(msg));
            }
        });
    }

    @Override
    public void sendMessage(UUID uuid, String message) {
        if (uuid == null || message == null) {
            return;
        }
        onMain(() -> {
            ServerPlayer p = player(uuid);
            if (p != null) {
                p.sendSystemMessage(Component.literal(message));
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
            PlayerList list = server.getPlayerList();
            // 以 UUID 構造 GameProfile：deop 以 profile 比對 ops.json，離線時亦生效（計劃 4.3）。
            GameProfile profile = resolveProfile(uuid);
            if (profile == null) {
                return;
            }
            try {
                if (list.isOp(profile)) {
                    list.deop(profile);
                }
            } catch (RuntimeException e) {
                log.warning("剝奪原版 OP 失敗（" + uuid + "）：" + e.getMessage());
            }
        });
    }

    @Override
    public void restoreVanillaOp(UUID uuid) {
        // 預設 no-op：理想是管理員完全不依賴原版 OP，權限全由本系統 transient 授予（計劃 4.3）。
        // 如部署確需原版 OP，可在此 list.op(profile)，但會擴大繞過面，不建議。
    }

    @Override
    public void sendChallenge(UUID uuid, byte[] nonce) {
        if (uuid == null || nonce == null) {
            return;
        }
        final byte[] payload = nonce.clone();
        onMain(() -> {
            final ServerPlayer p = player(uuid);
            if (p == null) {
                return;
            }
            try {
                // 1.20.1 以 Forge SimpleChannel 送出（見 NonceMsg）。沒有 1.20.5+ 的
                // PacketDistributor.sendToPlayer(player, payload)；改以
                // CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg)。
                // 盡力而為：對未安裝客戶端 Mod 者，逾時後走選項 B / 嚴格模式。
                NonceMsg.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> p),
                        new NonceMsg(payload));
            } catch (Throwable t) {
                // 客戶端未宣告支援此通道等情況不可拖垮驗證流程。
                // 客戶端對應（收 Nonce、加領域前綴簽名回傳）為獨立的 compile-only 客戶端 Mod，後續工作。
                log.fine("送出選項 A 挑戰失敗（" + uuid + "）：" + t.getMessage());
            }
        });
    }

    @Override
    public boolean isOnline(UUID uuid) {
        return player(uuid) != null;
    }

    @Override
    public Optional<String> getPlayerName(UUID uuid) {
        ServerPlayer p = player(uuid);
        if (p != null) {
            return Optional.of(p.getGameProfile().getName());
        }
        // 離線：嘗試自玩家檔案快取解析名稱。
        GameProfile profile = resolveProfile(uuid);
        if (profile != null && profile.getName() != null && !profile.getName().isBlank()) {
            return Optional.of(profile.getName());
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> getPlayerIp(UUID uuid) {
        ServerPlayer p = player(uuid);
        if (p == null) {
            return Optional.empty();
        }
        try {
            // 1.20.1：ServerGamePacketListenerImpl 直接以 public 欄位 connection 持有 Connection
            //（無 getConnection() 多載）。
            SocketAddress addr = p.connection.connection.getRemoteAddress();
            if (addr instanceof java.net.InetSocketAddress isa && isa.getAddress() != null) {
                // 僅回傳 IP 字串；雜湊由核心 AuditLog 以 HMAC-SHA256＋密鑰鹽處理（計劃 6.2）。
                return Optional.of(isa.getAddress().getHostAddress());
            }
        } catch (Throwable t) {
            log.fine("取得玩家 IP 失敗（" + uuid + "）：" + t.getMessage());
        }
        return Optional.empty();
    }

    // ── 生命週期清理（由 ZeroTrustForge 於停止時呼叫）─────────

    /**
     * 撤回所有 transient 授權、清空凍結狀態。於伺服器停止時呼叫（fail-closed，計劃 5.2）。
     * 須於主執行緒呼叫。
     */
    void revokeAllAndClear() {
        granted.clear();
        frozen.clear();
        frozenAnchor.clear();
    }

    // ── 內部 ────────────────────────────────────────────────

    private ServerPlayer player(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(uuid);
    }

    /**
     * 解析 {@link GameProfile}：線上玩家直接取；否則查伺服器的玩家檔案快取（{@code usercache.json}），
     * 取不到則以「僅 UUID」的 profile 後備（足供 deop 以 id 比對 ops.json）。
     */
    private GameProfile resolveProfile(UUID uuid) {
        ServerPlayer online = player(uuid);
        if (online != null) {
            return online.getGameProfile();
        }
        try {
            Optional<GameProfile> cached = server.getProfileCache() == null
                    ? Optional.empty()
                    : server.getProfileCache().get(uuid);
            if (cached.isPresent()) {
                return cached.get();
            }
        } catch (Throwable t) {
            log.fine("解析 GameProfile 失敗（" + uuid + "），改用僅 UUID profile：" + t.getMessage());
        }
        // ops.json 以 UUID 為鍵比對，名稱可為 null。
        return new GameProfile(uuid, null);
    }

    private void onMain(Runnable r) {
        if (server.isSameThread()) {
            safeRun(r);
        } else {
            try {
                server.execute(() -> safeRun(r));
            } catch (RuntimeException e) {
                // 伺服器停止後 execute 可能拒絕。
                log.fine("ZeroTrust 主執行緒派送失敗（伺服器可能停止中）：" + e.getMessage());
            }
        }
    }

    private void safeRun(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            log.severe("ZeroTrust 平台操作拋出例外：" + t);
        }
    }
}

package com.chococar.zerotrust.fabric;

import com.chococar.zerotrust.platform.PlatformAdapter;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Fabric（伺服器端）的 {@link PlatformAdapter} 實作（計劃 2.3 / 4.3 / 4.5 / 5.3）。
 * 名稱採用 <b>Mojang 官方對應</b>（{@code loom.officialMojangMappings()}）。
 *
 * <h2>執行緒模型</h2>
 * 引擎可能從任意執行緒（Discord 回呼、排程器）呼叫本介面。所有對伺服器世界 / 玩家的變更
 * <b>必須</b>在伺服器主執行緒進行，故凡有副作用的操作皆透過 {@link #onServer(Runnable)}
 * 以 {@link MinecraftServer#execute(Runnable)} 排回主執行緒（與 Paper 的 {@code onMain} 同策略）。
 *
 * <h2>權限（計劃 5.2 — 不可持久化）</h2>
 * Fabric <b>沒有</b>內建的動態權限 API。本介面以一個<b>純記憶體</b>的「已授權」集合
 * （{@link #granted}）表示管理員當前是否通過驗證；本 Mod 自身對受保護動作的把關
 * （見 {@link FreezeHandler} 等）一律查詢 {@link #isGranted(UUID)}。此集合：
 * <ul>
 *   <li>天生非持久化（重啟即空，符合「權限不持久化」不變式）。</li>
 *   <li>於 {@link #revokeAdminPerm}、登出、到期、撤銷、伺服器停止（{@link #revokeAllAndClear()}）
 *       一律主動清除（fail-closed）。</li>
 * </ul>
 * <p>若部署環境裝有 {@code fabric-permissions-api}（LuckPerms 等後端），未來可在
 * {@link #grantAdminPerm} / {@link #revokeAdminPerm} 額外橋接 transient node；本 MVP 不引入該相依，
 * 改以本系統自有的把關集合，避免擴大繞過面。
 *
 * <h2>原版 OP（計劃 4.3）</h2>
 * {@link #stripVanillaOp} 透過 {@code server.getPlayerList().removeFromOperators(GameProfile)}
 * 清除 {@code ops.json} 中的 OP；{@link #restoreVanillaOp} 預設 no-op（理想：管理員完全不用原版 OP）。
 */
final class FabricPlatformAdapter implements PlatformAdapter {

    private final MinecraftServer server;
    private final Logger log;
    private final Set<UUID> adminAccounts;

    /** 目前被凍結的玩家（執行緒安全；tick handler 與互動把關據此攔截）。 */
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    /** 凍結時捕捉的座標，tick handler 每 tick 將玩家拉回此處（僅保留視角）。 */
    private final Map<UUID, FrozenPosition> frozenPositions = new ConcurrentHashMap<>();
    /** 已通過驗證、目前被授予管理員權限者（純記憶體；本 Mod 自有把關）。 */
    private final Set<UUID> granted = ConcurrentHashMap.newKeySet();

    FabricPlatformAdapter(MinecraftServer server, Set<UUID> adminAccounts, Logger log) {
        this.server = Objects.requireNonNull(server, "server");
        this.adminAccounts = Set.copyOf(adminAccounts);
        this.log = Objects.requireNonNull(log, "log");
    }

    // ── 供其他元件查詢的狀態 ────────────────────────────────

    boolean isFrozen(UUID uuid) {
        return uuid != null && frozen.contains(uuid);
    }

    /** 該玩家當前是否被本系統授予管理員權限（受保護動作以此把關）。 */
    boolean isGranted(UUID uuid) {
        return uuid != null && granted.contains(uuid);
    }

    /** 凍結時捕捉的座標（tick handler 用；不存在表示未保存）。 */
    FrozenPosition frozenPosition(UUID uuid) {
        return uuid == null ? null : frozenPositions.get(uuid);
    }

    // ── PlatformAdapter ─────────────────────────────────────

    @Override
    public void freezePlayer(UUID uuid) {
        if (uuid == null) {
            return;
        }
        frozen.add(uuid);
        onServer(() -> {
            ServerPlayer p = player(uuid);
            if (p == null) {
                return;
            }
            // 捕捉當下座標供 tick handler 鎖位（僅一次；重複凍結不覆蓋）。
            frozenPositions.computeIfAbsent(uuid,
                    k -> new FrozenPosition(p.getX(), p.getY(), p.getZ()));
            // 無敵：防凍結期間死亡 / 掉落（計劃 4.5）。tick handler 亦每 tick 重申。
            p.setInvulnerable(true);
            p.setDeltaMovement(Vec3.ZERO);
            p.hurtMarked = true; // 立即同步速度歸零至客戶端。
        });
    }

    @Override
    public void unfreezePlayer(UUID uuid) {
        if (uuid == null) {
            return;
        }
        frozen.remove(uuid);
        frozenPositions.remove(uuid);
        onServer(() -> {
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
        // 純記憶體集合（非持久化）；本 Mod 對受保護動作以 isGranted 把關。
        granted.add(uuid);
    }

    @Override
    public void revokeAdminPerm(UUID uuid) {
        if (uuid == null) {
            return;
        }
        granted.remove(uuid);
    }

    @Override
    public void kickPlayer(UUID uuid, String reason) {
        if (uuid == null) {
            return;
        }
        final String msg = reason == null ? "" : stripLegacy(reason);
        onServer(() -> {
            ServerPlayer p = player(uuid);
            if (p != null) {
                // Mojang 對應：ServerPlayer.connection（ServerGamePacketListenerImpl）.disconnect(Component)。
                p.connection.disconnect(Component.literal(msg));
            }
        });
    }

    @Override
    public void sendMessage(UUID uuid, String message) {
        if (uuid == null || message == null) {
            return;
        }
        final String msg = stripLegacy(message);
        onServer(() -> {
            ServerPlayer p = player(uuid);
            if (p != null) {
                // 系統訊息（非聊天）；Mojang 對應：sendSystemMessage(Component)。
                p.sendSystemMessage(Component.literal(msg));
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
        onServer(() -> {
            ServerPlayer p = player(uuid);
            if (p == null) {
                // 離線：登入時（玩家在線）即已剝奪；此處無在線 GameProfile 可用，略過。
                // 完整離線剝奪（以 UUID 查 ops.json）為 follow-up，不影響登入即時剝奪的安全保證。
                return;
            }
            GameProfile profile = p.getGameProfile();
            try {
                // Mojang mappings: PlayerList.isOp / .deop（非 Yarn 的 isOperator/removeFromOperators）。
                if (server.getPlayerList().isOp(profile)) {
                    server.getPlayerList().deop(profile);
                }
            } catch (RuntimeException e) {
                log.warning("剝奪原版 OP 失敗（" + uuid + "）：" + e.getMessage());
            }
        });
    }

    @Override
    public void restoreVanillaOp(UUID uuid) {
        // 預設 no-op：理想是管理員完全不依賴原版 OP，權限全由本系統把關（計劃 4.3）。
    }

    @Override
    public void sendChallenge(UUID uuid, byte[] nonce) {
        if (uuid == null || nonce == null) {
            return;
        }
        final byte[] payload = nonce.clone();
        onServer(() -> {
            ServerPlayer p = player(uuid);
            if (p == null) {
                return;
            }
            try {
                // 盡力而為：目前尚無客戶端 Mod，無人回應亦無妨（逾時後走選項 B / 嚴格模式）。
                ServerPlayNetworking.send(p, new AuthPayload(payload));
            } catch (RuntimeException e) {
                // 通道未註冊或客戶端不在對應 channel 等狀況，不可拖垮驗證流程。
                log.fine("送出選項 A 挑戰失敗：" + e.getMessage());
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
            return Optional.ofNullable(p.getGameProfile().getName());
        }
        return Optional.empty();
    }

    // 注意：getPlayerIp 刻意沿用介面預設（回傳 empty）。核心遇此會省略審計日誌的 ip_hmac
    // 欄位（資料最小化，計劃 6.2），不影響任何驗證流程。
    // TODO(Phase 4 follow-up): 取 ServerPlayer 連線位址（Connection#getRemoteAddress 的 SocketAddress）
    //   並回傳 IP 字串，交由核心以 HMAC-SHA256 雜湊記錄。此處保守留空以確保跨對應版本穩定編譯。

    // ── 生命週期清理（伺服器停止時呼叫）────────────────────

    /**
     * 撤回所有授權並清空凍結狀態（fail-closed，計劃 5.2）。於伺服器停止時呼叫。
     * 本方法將清理動作排入主執行緒。
     */
    void revokeAllAndClear() {
        granted.clear();
        frozen.clear();
        frozenPositions.clear();
        onServer(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p != null) {
                    p.setInvulnerable(false);
                }
            }
        });
    }

    // ── 內部 ────────────────────────────────────────────────

    /** 取得在線玩家（僅在主執行緒呼叫；查詢本身輕量，跨執行緒讀取在實務上安全）。 */
    private ServerPlayer player(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(uuid);
    }

    /** 將副作用排回伺服器主執行緒並包覆例外保護。 */
    private void onServer(Runnable r) {
        try {
            server.execute(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    log.severe("ZeroTrust 平台操作拋出例外：" + t);
                }
            });
        } catch (Throwable t) {
            // 伺服器關閉時 execute 可能拒收。
            log.fine("無法將平台操作排入主執行緒（伺服器可能正在關閉）：" + t);
        }
    }

    /**
     * 移除 Minecraft 傳統 {@code §} 顏色碼（核心訊息為 Paper 風格，含 {@code §a}/{@code §c} 等）。
     * Fabric 的 {@link Component#literal(String)} 不解析傳統碼，故此處剝除，避免畫面出現亂碼。
     */
    static String stripLegacy(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                i++; // 跳過 § 與其後一個格式字元。
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 凍結時捕捉的座標（僅位置；視角不鎖）。 */
    record FrozenPosition(double x, double y, double z) {}
}

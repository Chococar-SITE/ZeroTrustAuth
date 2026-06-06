package com.chococar.zerotrust.fabric;

import com.chococar.zerotrust.EngineContext;
import com.chococar.zerotrust.ZeroTrustCore;
import com.chococar.zerotrust.auth.AuthEngine;
import com.chococar.zerotrust.auth.SelfTest;
import com.chococar.zerotrust.common.DiscordNotifier;
import com.chococar.zerotrust.common.FileLogSink;
import com.chococar.zerotrust.common.YamlConfigLoader;
import com.chococar.zerotrust.common.YamlKeyRepository;
import com.chococar.zerotrust.config.ZeroTrustConfig;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * ZeroTrustAuth 的 Fabric（伺服器端）進入點（計劃 Phase 4 / 5.3）。
 * 鏡像 Paper 的 {@code ZeroTrustPlugin} 接線與邏輯，但以 Fabric API 事件與 Mojang 對應實作。
 *
 * <h2>生命週期</h2>
 * <ul>
 *   <li><b>{@link #onInitializeServer()}（載入器初始化）：</b>註冊自訂封包型別（S2C / C2S）、
 *       互動 / 聊天攔截、{@code /authkey} 指令樹（以 {@link AtomicReference} 在執行時解析引擎）、
 *       以及伺服器生命週期事件。<b>不</b>在此建立引擎（尚無 {@link MinecraftServer}）。</li>
 *   <li><b>{@code SERVER_STARTING}：</b>讀設定 / 秘密、建構所有依賴、建立 {@link ZeroTrustCore}、
 *       執行啟動自檢（失敗且 fail-closed → 安全模式），並非同步啟動 Discord（選項 B）。</li>
 *   <li><b>{@code SERVER_STOPPING}：</b>{@code core.shutdown()} +
 *       平台層撤權清理 + 關閉 Discord / 日誌 / 排程（fail-closed，計劃 5.2）。</li>
 * </ul>
 *
 * <h2>安全不變式（CLAUDE.md）</h2>
 * <ul>
 *   <li>秘密（{@code DISCORD_BOT_TOKEN}、{@code IP_HMAC_SECRET}）<b>僅</b>從環境變數讀取。</li>
 *   <li>權限以純記憶體把關集合授予（{@link FabricPlatformAdapter}），非持久化。</li>
 *   <li>伺服器停止主動撤回所有授權與凍結（fail-closed）。</li>
 * </ul>
 */
public final class ZeroTrustFabric implements DedicatedServerModInitializer {

    /** 與遊戲載入器無關的 JUL logger（{@link DiscordNotifier} / {@link FileLogSink} 等共用基礎建設沿用）。 */
    private static final Logger LOG = Logger.getLogger("ZeroTrustAuth");

    /** 資料夾名（設定 / 金鑰 / 日誌均置於 config/zerotrustauth/）。 */
    private static final String DATA_DIR_NAME = "zerotrustauth";

    /** 執行時解析引擎用（指令樹於 MinecraftServer 建構期即註冊，早於引擎建立）。 */
    private final AtomicReference<AuthEngine> engineRef = new AtomicReference<>();

    /** 每次登入的連線 ID（listener 寫入、command 讀取）。 */
    private final java.util.Map<UUID, String> connectionIds = new ConcurrentHashMap<>();

    // 建構於 SERVER_STARTING、清理於 SERVER_STOPPING。
    private ZeroTrustCore core;
    private FabricPlatformAdapter adapter;
    private FabricScheduler scheduler;
    private DiscordNotifier notifier;
    private FileLogSink logSink;
    private FreezeHandler freezeHandler;

    @Override
    public void onInitializeServer() {
        // 1) 註冊自訂封包型別（必須早於任何接收器；S2C 用於送 Nonce，C2S 用於收簽名）。
        PayloadTypeRegistry.clientboundPlay().register(AuthPayload.TYPE, AuthPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AuthPayload.TYPE, AuthPayload.STREAM_CODEC);

        // 2) 指令樹：執行時才解析引擎（CommandRegistrationCallback 早於引擎建立）。
        AuthKeyCommand command = new AuthKeyCommand(engineRef::get, connectionIds);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                command.register(dispatcher));

        // 3) 每 tick 凍結位置鎖（引擎尚未建立時 freezeHandler 為 null → 略過）。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            FreezeHandler fh = this.freezeHandler;
            if (fh != null) {
                fh.onEndServerTick(server);
            }
        });

        // 4) 伺服器生命週期：啟動時建引擎、停止時清理。
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        LOG.info("ZeroTrustAuth（Fabric）已初始化，等待伺服器啟動以建立引擎。");
    }

    // ── 伺服器啟動：建構引擎（鏡像 Paper onEnable）────────────

    private void onServerStarting(MinecraftServer server) {
        Path dataDir = FabricLoader.getInstance().getConfigDir().resolve(DATA_DIR_NAME);
        Path configPath = dataDir.resolve("config.yml");
        Path keysPath = dataDir.resolve("keys.yml");

        // ── 解析設定（fail-closed：IO / 解析失敗 → 安全模式）──
        YamlConfigLoader.Loaded loaded;
        try {
            loaded = YamlConfigLoader.load(configPath, LOG);
        } catch (RuntimeException e) {
            LOG.severe("設定解析失敗，進入安全模式（拒絕所有授權）：" + e.getMessage());
            // 仍建立最小引擎並立即進安全模式，確保管理員一律被凍結拒絕。
            buildInSafeMode(server, "設定解析失敗：" + e.getMessage());
            return;
        }
        ZeroTrustConfig config = loaded.config;
        Set<UUID> adminSet = new HashSet<>(loaded.admins);

        // ── 秘密（僅環境變數，計劃 6.2 / 8）──
        String discordToken = System.getenv("DISCORD_BOT_TOKEN");
        String ipHmacSecretEnv = System.getenv("IP_HMAC_SECRET");
        byte[] ipHmacSecret = ipHmacSecretEnv == null
                ? new byte[0]
                : ipHmacSecretEnv.getBytes(StandardCharsets.UTF_8);

        // ── 建構依賴 ──
        this.adapter = new FabricPlatformAdapter(server, adminSet, LOG);
        this.scheduler = new FabricScheduler(server, LOG);
        this.notifier = new DiscordNotifier(
                discordToken, loaded.discordAdminId, loaded.discordFallbackChannelId, LOG);

        YamlKeyRepository keyRepository = new YamlKeyRepository(keysPath, LOG);

        // 日誌檔初始化（IO 失敗 → fail-closed 安全模式）。
        try {
            this.logSink = new FileLogSink(dataDir, config.logRetentionDays(), LOG);
        } catch (RuntimeException e) {
            LOG.severe("日誌檔初始化失敗，進入安全模式：" + e.getMessage());
            buildInSafeMode(server, "日誌初始化失敗：" + e.getMessage());
            return;
        }

        EngineContext ctx = EngineContext.builder()
                .config(config)
                .adapter(adapter)
                .scheduler(scheduler)
                .notifier(notifier)
                .logSink(logSink)
                .keyRepository(keyRepository)
                .ipHmacSecret(ipHmacSecret)
                .build();

        // ── 建立核心引擎 ──
        this.core = new ZeroTrustCore(ctx);
        this.engineRef.set(core);

        // ── 註冊事件接線（連線生命週期 + C2S 回應；tick 已於 onInitializeServer 接好）──
        this.freezeHandler = new FreezeHandler(core, adapter);
        this.freezeHandler.registerInteractionGuards();
        new ConnectionListener(core, adapter, connectionIds).register();

        // ── 啟動自檢（計劃 6.3）──
        // 權限後端「已載入」：Fabric 以本系統自有把關集合授權，永遠可用 → true（與 Paper attachment 後備同義）。
        SelfTest.Report report = SelfTest.run(ctx, true);
        // summary 以 "SELF-TEST PASSED" / "SELF-TEST FAILED" 開頭（CI 整合標記，與 Paper 一致）。
        LOG.info(report.summary());

        if (!report.passed() && config.failClosed()) {
            core.enterSafeMode("啟動自檢失敗：" + report.failures());
            LOG.severe("SAFE MODE 已啟用——將拒絕所有管理員授權。失敗項：" + report.failures());
        }

        // ── 非同步啟動 Discord（連線可能耗時，不阻塞啟動）──
        Thread discordStart = new Thread(() -> {
            try {
                notifier.start();
            } catch (Throwable t) {
                LOG.warning("Discord 啟動執行緒異常（選項 B 將不可用）：" + t);
            }
        }, "zerotrust-discord-start");
        discordStart.setDaemon(true);
        discordStart.start();

        LOG.info("ZeroTrustAuth（Fabric）已啟用（管理員帳號數：" + adminSet.size()
                + "，權限把關：記憶體集合，權限 node 設定：" + loaded.adminPermissionNode + "）。");
    }

    /**
     * 在無法正常建構（設定 / 日誌失敗）時，仍建立一個可運作的引擎並立即進入安全模式，
     * 確保管理員一律被凍結且拒絕授權（fail-closed）。使用內建安全預設值與 no-op 日誌。
     */
    private void buildInSafeMode(MinecraftServer server, String reason) {
        try {
            if (this.adapter == null) {
                this.adapter = new FabricPlatformAdapter(server, Set.of(), LOG);
            }
            if (this.scheduler == null) {
                this.scheduler = new FabricScheduler(server, LOG);
            }
            if (this.notifier == null) {
                this.notifier = new DiscordNotifier(null, "", "", LOG);
            }
            // no-op 日誌：避免再次 IO；安全模式不需審計（一律拒絕）。
            com.chococar.zerotrust.audit.LogSink noop = line -> { };
            EngineContext ctx = EngineContext.builder()
                    .config(ZeroTrustConfig.defaults())
                    .adapter(adapter)
                    .scheduler(scheduler)
                    .notifier(notifier)
                    .logSink(noop)
                    .keyRepository(new com.chococar.zerotrust.platform.KeyRepository() {
                        @Override public java.util.Map<UUID, java.util.List<com.chococar.zerotrust.platform.StoredKey>> loadAll() {
                            return java.util.Map.of();
                        }
                        @Override public void save(UUID uuid, java.util.List<com.chococar.zerotrust.platform.StoredKey> keys) { }
                    })
                    .ipHmacSecret(new byte[0])
                    .build();
            this.core = new ZeroTrustCore(ctx);
            this.engineRef.set(core);
            this.freezeHandler = new FreezeHandler(core, adapter);
            this.freezeHandler.registerInteractionGuards();
            new ConnectionListener(core, adapter, connectionIds).register();
            core.enterSafeMode(reason);
            LOG.severe("ZeroTrustAuth（Fabric）以安全模式啟動：" + reason);
        } catch (Throwable t) {
            // 連安全模式都建不起來：記錄嚴重錯誤；無引擎時管理員仍不會被授權（無人解凍）。
            LOG.severe("無法建立安全模式引擎：" + t);
        }
    }

    // ── 伺服器停止：清理（鏡像 Paper onDisable）─────────────

    private void onServerStopping(MinecraftServer server) {
        // fail-closed（計劃 5.2）：主動撤回所有權限與凍結，清空 Session。
        try {
            if (core != null) {
                core.shutdown();
            }
        } catch (Throwable t) {
            LOG.severe("核心 shutdown 發生例外：" + t);
        }
        // 平台層再防禦性清一次。
        try {
            if (adapter != null) {
                adapter.revokeAllAndClear();
            }
        } catch (Throwable t) {
            LOG.severe("平台層撤權清理發生例外：" + t);
        }
        // 關閉排程、Discord、日誌。
        try {
            if (scheduler != null) {
                scheduler.shutdown();
            }
        } catch (Throwable t) {
            LOG.warning("關閉排程時發生例外：" + t);
        }
        try {
            if (notifier != null) {
                notifier.shutdown();
            }
        } catch (Throwable t) {
            LOG.warning("關閉 Discord 時發生例外：" + t);
        }
        try {
            if (logSink != null) {
                logSink.close();
            }
        } catch (Throwable t) {
            LOG.warning("關閉日誌時發生例外：" + t);
        }
        connectionIds.clear();
        engineRef.set(null);
        LOG.info("ZeroTrustAuth（Fabric）已停用，已撤回所有管理員權限。");
    }
}

package com.chococar.zerotrust.neoforge;

import com.chococar.zerotrust.EngineContext;
import com.chococar.zerotrust.ZeroTrustCore;
import com.chococar.zerotrust.audit.LogSink;
import com.chococar.zerotrust.auth.AuthEngine;
import com.chococar.zerotrust.auth.SelfTest;
import com.chococar.zerotrust.common.DiscordNotifier;
import com.chococar.zerotrust.common.FileLogSink;
import com.chococar.zerotrust.common.YamlConfigLoader;
import com.chococar.zerotrust.common.YamlKeyRepository;
import com.chococar.zerotrust.config.ZeroTrustConfig;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * ZeroTrustAuth 的 NeoForge（伺服器端）進入點（計劃 Phase 4）。
 *
 * <p>接線方式與 Paper（{@code ZeroTrustPlugin}）/ Fabric 一致：於伺服器啟動時蒐集所有依賴
 * （adapter / scheduler / notifier=共用 {@code DiscordNotifier} / logSink=共用 {@code FileLogSink} /
 * keyRepository=共用 {@code YamlKeyRepository}）建立 {@link EngineContext} → {@code new ZeroTrustCore(ctx)}，
 * 執行啟動自檢，自檢失敗且 {@code fail_closed} 時進入<b>安全模式</b>（拒絕所有授權）。
 *
 * <h2>安全不變式（CLAUDE.md）</h2>
 * <ul>
 *   <li>秘密（{@code DISCORD_BOT_TOKEN}、{@code IP_HMAC_SECRET}）僅自環境變數讀取，絕不入設定 / 日誌。</li>
 *   <li>權限僅以記憶體 transient 集合授予（見 {@link NeoForgePlatformAdapter}）。</li>
 *   <li>{@link #onServerStopping} 主動撤回所有權限與凍結（fail-closed，計劃 5.2）。</li>
 * </ul>
 *
 * <h2>事件匯流排</h2>
 * <ul>
 *   <li><b>Mod 匯流排</b>（建構子注入的 {@link IEventBus}）：{@link RegisterPayloadHandlersEvent}
 *       （選項 A 挑戰自訂封包註冊）。</li>
 *   <li><b>遊戲匯流排</b>（{@link NeoForge#EVENT_BUS}）：伺服器生命週期、玩家登入 / 登出、指令註冊、
 *       以及（引擎建立後）{@link FreezeHandler} 的凍結強制。</li>
 * </ul>
 */
@Mod("zerotrustauth")
public final class ZeroTrustNeoForge {

    private final Logger log = Logger.getLogger("ZeroTrustAuth");

    // 引擎與依賴：於 ServerAboutToStartEvent 建立，ServerStoppingEvent 清理。volatile 供指令延遲解析。
    private volatile ZeroTrustCore core;
    private NeoForgePlatformAdapter adapter;
    private NeoForgeScheduler scheduler;
    private DiscordNotifier notifier;
    private LogSink logSink;
    private YamlKeyRepository keyRepository;
    private FreezeHandler freezeHandler;

    /** 每次登入的連線 ID（登入事件寫入、command verify 讀取）。 */
    private final Map<UUID, String> connectionIds = new ConcurrentHashMap<>();

    /** NeoForge 以建構子注入 mod 匯流排與容器。 */
    public ZeroTrustNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        // Mod 匯流排：註冊選項 A 挑戰封包。
        modEventBus.addListener(this::onRegisterPayloads);
        // 遊戲匯流排：伺服器生命週期、玩家事件、指令註冊（@SubscribeEvent 實例方法）。
        NeoForge.EVENT_BUS.register(this);
        log.info("ZeroTrustAuth (NeoForge) 已載入，等待伺服器啟動。");
    }

    // ── Mod 匯流排：封包註冊 ─────────────────────────────────

    /**
     * 註冊選項 A 挑戰封包（{@code zerotrust:auth}）。以 {@code optional()} 註冊：對未安裝本 mod 的
     * 客戶端，NeoForge 會略過送出而非拋例外（與 Paper 的「盡力而為」一致）。本 mod 為伺服器端，
     * 僅<b>送出</b>此封包；client handler 為 no-op（伺服器端永不被呼叫）。
     */
    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("zerotrustauth").optional();
        // 雙向：S2C 送出挑戰 Nonce；C2S 接收客戶端 Mod 的簽名回應（與 Fabric 一致）。
        registrar.playBidirectional(
                AuthChallengePayload.TYPE,
                AuthChallengePayload.STREAM_CODEC,
                (payload, context) -> {
                    // 僅處理伺服器端收到的 C2S 回應；格式：nonceLen(1) || nonce || signature。
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }
                    AuthEngine engine = engine();
                    if (engine == null) {
                        return;
                    }
                    byte[] data = payload.nonce();
                    if (data == null || data.length < 1) {
                        return;
                    }
                    int nonceLen = data[0] & 0xFF;
                    if (1 + nonceLen > data.length) {
                        return; // 畸形封包，忽略（fail-closed：不觸發驗證）。
                    }
                    byte[] nonce = java.util.Arrays.copyOfRange(data, 1, 1 + nonceLen);
                    byte[] signature = java.util.Arrays.copyOfRange(data, 1 + nonceLen, data.length);
                    UUID uuid = player.getUUID();
                    String connectionId = connectionIds.get(uuid);
                    context.enqueueWork(() -> engine.onSignatureResponse(uuid, connectionId, nonce, signature));
                });
    }

    // ── 遊戲匯流排：伺服器生命週期 ───────────────────────────

    /**
     * 伺服器即將啟動：建立引擎。選在此最早的伺服器事件，確保引擎於
     * {@link RegisterCommandsEvent} 之後玩家實際執行指令前即就緒（指令本身以延遲解析容錯）。
     */
    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        try {
            buildEngine(server);
        } catch (Throwable t) {
            // 任何接線失敗一律 fail-closed：嘗試進入安全模式；若連核心都未建成，記錄嚴重錯誤。
            log.severe("ZeroTrustAuth 啟動接線失敗：" + t);
            if (core != null) {
                core.enterSafeMode("啟動接線例外：" + t.getMessage());
            }
        }
    }

    private void buildEngine(MinecraftServer server) {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("zerotrustauth");
        Path configFile = configDir.resolve("config.yml");
        Path keysFile = configDir.resolve("keys.yml");

        // ── 解析設定（共用 SnakeYAML loader；不存在則寫出安全預設）──
        YamlConfigLoader.Loaded loaded = YamlConfigLoader.load(configFile, log);
        ZeroTrustConfig config = loaded.config;
        Set<UUID> adminSet = new HashSet<>(loaded.admins);

        // ── 秘密（僅環境變數，計劃 6.2 / 8）──
        String discordToken = System.getenv("DISCORD_BOT_TOKEN");
        String ipHmacSecretEnv = System.getenv("IP_HMAC_SECRET");
        byte[] ipHmacSecret = ipHmacSecretEnv == null
                ? new byte[0]
                : ipHmacSecretEnv.getBytes(StandardCharsets.UTF_8);

        // ── 建構依賴 ──
        this.adapter = new NeoForgePlatformAdapter(server, adminSet, log);
        this.scheduler = new NeoForgeScheduler(server, log);
        this.keyRepository = new YamlKeyRepository(keysFile, log);
        this.notifier = new DiscordNotifier(
                discordToken, loaded.discordAdminId, loaded.discordFallbackChannelId, log);

        // 日誌檔（IO 失敗 → fail-closed：標記後續進入安全模式）。logs/ 建於 config/zerotrustauth/ 下。
        boolean logSinkOk = true;
        try {
            this.logSink = new FileLogSink(configDir, config.logRetentionDays(), log);
        } catch (RuntimeException e) {
            log.severe("日誌檔初始化失敗，將進入安全模式：" + e.getMessage());
            logSinkOk = false;
            // 後備：以無作用 sink 讓引擎仍可建立，但隨即進安全模式。
            this.logSink = line -> { };
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

        // ── 啟動自檢（計劃 6.3）──
        // NeoForge 無外部權限後端依賴：以記憶體 transient 集合授權，故「權限後端已載入」恆為 true。
        SelfTest.Report report = SelfTest.run(ctx, true);
        // summary 以 "SELF-TEST PASSED" / "SELF-TEST FAILED" 開頭（CI 整合標記）。
        log.info(report.summary());

        if ((!report.passed() || !logSinkOk) && config.failClosed()) {
            String reason = !logSinkOk ? "日誌初始化失敗" : ("啟動自檢失敗：" + report.failures());
            core.enterSafeMode(reason);
            log.severe("SAFE MODE 已啟用——將拒絕所有管理員授權。原因：" + reason);
        }

        // ── 註冊凍結強制（遊戲匯流排）──
        this.freezeHandler = new FreezeHandler(core, adapter, server);
        NeoForge.EVENT_BUS.register(freezeHandler);

        // ── 非同步啟動 Discord（連線可能耗時，不阻塞伺服器啟動）──
        Thread discordStart = new Thread(() -> {
            try {
                notifier.start();
            } catch (Throwable t) {
                log.warning("Discord 啟動執行緒異常（選項 B 將不可用）：" + t);
            }
        }, "zerotrust-discord-start");
        discordStart.setDaemon(true);
        discordStart.start();

        log.info("ZeroTrustAuth 引擎已建立（管理員帳號數：" + adminSet.size()
                + "，權限後端：記憶體 transient，設定目錄：" + configDir + "）。");
    }

    /** 伺服器停止：fail-closed 清理（撤回所有權限與凍結、取消任務、關閉 Discord 與日誌）。 */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 核心 shutdown：撤回所有已驗證管理員權限、清空 Session、取消排程任務、關閉日誌（計劃 5.2）。
        try {
            if (core != null) {
                core.shutdown();
            }
        } catch (Throwable t) {
            log.severe("核心 shutdown 發生例外：" + t);
        }
        // 平台層再防禦性清一次。
        try {
            if (adapter != null) {
                adapter.revokeAllAndClear();
            }
        } catch (Throwable t) {
            log.severe("平台層撤權清理發生例外：" + t);
        }
        // 取消排程計時器。
        try {
            if (scheduler != null) {
                scheduler.shutdown();
            }
        } catch (Throwable t) {
            log.warning("關閉排程器時發生例外：" + t);
        }
        // 關閉 Discord。
        try {
            if (notifier != null) {
                notifier.shutdown();
            }
        } catch (Throwable t) {
            log.warning("關閉 Discord 時發生例外：" + t);
        }
        // 關閉日誌（核心 shutdown 已關一次；此處冪等再保險）。
        try {
            if (logSink != null) {
                logSink.close();
            }
        } catch (Throwable t) {
            log.warning("關閉日誌時發生例外：" + t);
        }
        connectionIds.clear();
        log.info("ZeroTrustAuth 已停用，已撤回所有管理員權限。");
    }

    // ── 遊戲匯流排：指令註冊 ─────────────────────────────────

    /**
     * 註冊 {@code /authkey}。此事件可能早於引擎建立觸發；指令以延遲 {@link AuthEngine} 解析容錯
     * （見 {@link AuthKeyCommand}）。指令會於每次資料包重載時重建，故引擎建立後仍有效。
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        AuthKeyCommand cmd = new AuthKeyCommand(this::engine, connectionIds);
        cmd.register(event.getDispatcher());
    }

    // ── 遊戲匯流排：玩家登入 / 登出 ───────────────────────────

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        AuthEngine engine = this.core;
        if (engine == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID uuid = player.getUUID();
        if (!adapter.isAdminAccount(uuid)) {
            return; // 一般玩家零感知。
        }
        // 每次登入產生新連線 ID，將 Nonce 綁定至此連線（計劃 3.2 / 3.5）。
        String connectionId = UUID.randomUUID().toString();
        connectionIds.put(uuid, connectionId);
        // 引擎會凍結、剝奪原版 OP、送出凍結提示並啟動驗證；此處不重複送提示。
        engine.onAdminJoin(uuid, player.getGameProfile().getName(), connectionId);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        AuthEngine engine = this.core;
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID uuid = player.getUUID();
        connectionIds.remove(uuid);
        if (engine != null && adapter != null && adapter.isAdminAccount(uuid)) {
            engine.onAdminQuit(uuid);
        }
    }

    // ── 內部 ────────────────────────────────────────────────

    /** 供指令延遲解析引擎（建立前回 {@code null}）。 */
    private AuthEngine engine() {
        return this.core;
    }
}

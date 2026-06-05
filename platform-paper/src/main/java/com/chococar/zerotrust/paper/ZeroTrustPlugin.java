package com.chococar.zerotrust.paper;

import com.chococar.zerotrust.EngineContext;
import com.chococar.zerotrust.ZeroTrustCore;
import com.chococar.zerotrust.auth.SelfTest;
import com.chococar.zerotrust.config.ZeroTrustConfig;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ZeroTrustAuth 的 Paper / Spigot 進入點（計劃 Phase 2 MVP）。
 *
 * <p>串接核心引擎所有依賴（adapter / scheduler / notifier / logSink / keyRepository），執行啟動自檢，
 * 並在自檢失敗且 {@code fail_closed} 時進入<b>安全模式</b>（拒絕所有授權）。
 *
 * <h2>安全不變式（CLAUDE.md）</h2>
 * <ul>
 *   <li>秘密（{@code DISCORD_BOT_TOKEN}、{@code IP_HMAC_SECRET}）僅從環境變數讀取，絕不入設定 / 日誌。</li>
 *   <li>權限僅以 transient / attachment 授予（見 {@link PaperPlatformAdapter}）。</li>
 *   <li>{@link #onDisable()} 主動撤回所有權限與凍結（fail-closed，計劃 5.2）。</li>
 * </ul>
 */
public final class ZeroTrustPlugin extends JavaPlugin {

    private ZeroTrustCore core;
    private PaperPlatformAdapter adapter;
    private DiscordNotifier notifier;
    private FileLogSink logSink;
    private YamlKeyRepository keyRepository;

    /** 每次登入的連線 ID（listener 寫入、command 讀取）。 */
    private final Map<UUID, String> connectionIds = new ConcurrentHashMap<>();

    /** 內嵌的預設設定資源名稱。
     *  <p>注意：不可命名為 {@code config.yml}——本專案 {@code .gitignore} 有一條保護用的
     *  {@code config.yml} 規則（避免提交含密的真實設定），會連帶把此內嵌資源排除於版控與 jar 之外，
     *  導致 {@code saveDefaultConfig()} 在執行時找不到資源而拋例外。故改用未被忽略的檔名，並於
     *  {@link #ensureDefaultConfig()} 手動複製成資料夾內的 {@code config.yml}。 */
    static final String DEFAULT_CONFIG_RESOURCE = "config-default.yml";

    @Override
    public void onEnable() {
        ensureDefaultConfig();
        FileConfiguration yaml = getConfig();

        // ── 解析設定 ──（fail-closed：解析在純資料層，副作用由本方法承擔）
        ConfigLoader.Loaded loaded;
        try {
            loaded = ConfigLoader.load(yaml, getLogger());
        } catch (RuntimeException e) {
            getLogger().severe("設定解析失敗，進入安全模式（拒絕所有授權）：" + e.getMessage());
            // 無法解析設定即無法安全運作；停用外掛以 fail-closed。
            getServer().getPluginManager().disablePlugin(this);
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
        this.adapter = new PaperPlatformAdapter(this, adminSet, loaded.adminPermissionNode);
        PaperScheduler scheduler = new PaperScheduler(this);
        this.keyRepository = new YamlKeyRepository(this);
        this.notifier = new DiscordNotifier(
                discordToken, loaded.discordAdminId, loaded.discordFallbackChannelId, getLogger());

        // 日誌檔初始化（IO 失敗 → fail-closed 安全模式）。
        try {
            this.logSink = new FileLogSink(getDataFolder().toPath(), config.logRetentionDays(), getLogger());
        } catch (RuntimeException e) {
            getLogger().severe("日誌檔初始化失敗，進入安全模式：" + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
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

        // ── 啟動自檢（計劃 6.3）──
        // 權限後端「已載入」：有 LuckPerms 即 true；無 LuckPerms 時，除非設定嚴格要求，否則
        // Bukkit attachment 後備永遠存在 → 視為已載入。
        boolean permissionBackendLoaded = adapter.usingLuckPerms() || !loaded.requireLuckPerms;
        SelfTest.Report report = SelfTest.run(ctx, permissionBackendLoaded);
        // summary 以 "SELF-TEST PASSED" / "SELF-TEST FAILED" 開頭（CI 整合標記）。
        getLogger().info(report.summary());

        if (!report.passed() && config.failClosed()) {
            core.enterSafeMode("啟動自檢失敗：" + report.failures());
            getLogger().severe("SAFE MODE 已啟用——將拒絕所有管理員授權。失敗項：" + report.failures());
        }

        // ── 註冊事件、指令、Plugin Message 通道 ──
        FreezeListener listener = new FreezeListener(core, adapter, connectionIds);
        getServer().getPluginManager().registerEvents(listener, this);

        AuthKeyCommand cmd = new AuthKeyCommand(core, connectionIds);
        PluginCommand authkey = getCommand("authkey");
        if (authkey != null) {
            authkey.setExecutor(cmd);
            authkey.setTabCompleter(cmd);
        } else {
            getLogger().severe("找不到 'authkey' 指令定義（plugin.yml）——指令將無法使用。");
        }

        // 註冊選項 A 挑戰用的出站 Plugin Message 通道。
        getServer().getMessenger().registerOutgoingPluginChannel(this, PaperPlatformAdapter.CHANNEL);

        // ── 非同步啟動 Discord（連線可能耗時，不阻塞 onEnable）──
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                notifier.start();
            } catch (Throwable t) {
                getLogger().warning("Discord 啟動執行緒異常（選項 B 將不可用）：" + t);
            }
        });

        getLogger().info("ZeroTrustAuth 已啟用（管理員帳號數：" + adminSet.size()
                + "，權限後端：" + (adapter.usingLuckPerms() ? "LuckPerms(transient)" : "Bukkit attachment")
                + "，權限 node：" + loaded.adminPermissionNode + "）。");
    }

    /**
     * 確保資料夾內存在 {@code config.yml}：不存在時，從內嵌的 {@link #DEFAULT_CONFIG_RESOURCE}
     * 複製一份過去，再讓 {@link #getConfig()} 讀取。等同 {@code saveDefaultConfig()}，但避開
     * {@code config.yml} 被 {@code .gitignore} 排除而無法內嵌的問題（見該常數註解）。
     *
     * <p>複製失敗不致命：{@link #getConfig()} 對缺檔會回傳空設定，{@link ConfigLoader} 則套用
     * 計劃 7.3 的安全預設值（{@code fail_closed=true} 等），自檢仍可通過。
     */
    private void ensureDefaultConfig() {
        File target = new File(getDataFolder(), "config.yml");
        if (target.exists()) {
            return;
        }
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                getLogger().warning("無法建立資料夾：" + getDataFolder());
            }
            try (InputStream in = getResource(DEFAULT_CONFIG_RESOURCE)) {
                if (in == null) {
                    // 內嵌資源遺失（理論上不會發生）：留給 ConfigLoader 套用安全預設值。
                    getLogger().warning("內嵌預設設定 " + DEFAULT_CONFIG_RESOURCE
                            + " 遺失；將以內建安全預設值運作。");
                    return;
                }
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                getLogger().info("已從內嵌預設建立 config.yml。");
            }
        } catch (IOException | RuntimeException e) {
            // 不丟出 onEnable：以安全預設值繼續（fail-closed 由 ConfigLoader/SelfTest 保證）。
            getLogger().warning("建立預設 config.yml 失敗（將以內建安全預設值運作）：" + e.getMessage());
        }
        // 確保 getConfig() 反映剛寫入的檔案。
        reloadConfig();
    }

    @Override
    public void onDisable() {
        // fail-closed（計劃 5.2）：主動撤回所有權限與凍結，清空 Session。
        try {
            if (core != null) {
                core.shutdown();
            }
        } catch (Throwable t) {
            getLogger().severe("核心 shutdown 發生例外：" + t);
        }

        // 縱使核心已盡力撤回，平台層再防禦性清一次（onDisable 必在主執行緒）。
        try {
            if (adapter != null) {
                adapter.revokeAllAndClear();
            }
        } catch (Throwable t) {
            getLogger().severe("平台層撤權清理發生例外：" + t);
        }

        // 取消通道註冊。
        try {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, PaperPlatformAdapter.CHANNEL);
        } catch (Throwable ignored) {
            // 伺服器關閉時 messenger 可能已清理。
        }

        // 關閉 Discord 與日誌。
        try {
            if (notifier != null) {
                notifier.shutdown();
            }
        } catch (Throwable t) {
            getLogger().warning("關閉 Discord 時發生例外：" + t);
        }
        try {
            if (logSink != null) {
                logSink.close();
            }
        } catch (Throwable t) {
            getLogger().warning("關閉日誌時發生例外：" + t);
        }

        connectionIds.clear();
        getLogger().info("ZeroTrustAuth 已停用，已撤回所有管理員權限。");
    }
}

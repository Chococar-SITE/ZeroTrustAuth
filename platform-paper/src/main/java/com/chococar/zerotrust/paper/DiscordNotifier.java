package com.chococar.zerotrust.paper;

import com.chococar.zerotrust.notify.AlertLevel;
import com.chococar.zerotrust.notify.ConfirmResult;
import com.chococar.zerotrust.notify.Notifier;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * {@link Notifier} 的 Discord（JDA 5）實作——選項 B 帶外驗證與警報（計劃 3.3 / 6.6 / 6.7）。
 *
 * <h2>降級與健全性</h2>
 * <ul>
 *   <li>Token 為空 → {@link #isAvailable()} 永遠 false；{@link #requestLoginConfirmation} 立即回
 *       {@link ConfirmResult#SEND_FAILED}；{@link #notice}/{@link #alert} 僅記 console。</li>
 *   <li>有 Token → 非同步建立 JDA；{@link #isAvailable()} 反映連線狀態。</li>
 *   <li>DM 開啟失敗 → 退回 {@code fallbackChannelId}；再失敗 → {@code SEND_FAILED}。</li>
 *   <li>任何 JDA 例外一律 <b>記錄並優雅降級，絕不</b>向引擎拋出。</li>
 * </ul>
 *
 * <h2>確認流程</h2>
 * 每次請求產生一次性 token，編入兩顆按鈕的 ID（{@code zt:confirm:<token>} / {@code zt:deny:<token>}）。
 * {@link Listener} 收到對應按鈕互動即完成 {@link CompletableFuture}（CONFIRMED / DENIED），並排程
 * TTL 逾時 → TIMEOUT。token 用後即從表中移除（一次性）。
 *
 * <p><b>秘密：</b>Bot Token 僅由建構子（來自環境變數）傳入，絕不記錄。
 */
final class DiscordNotifier implements Notifier {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private static final String BTN_CONFIRM_PREFIX = "zt:confirm:";
    private static final String BTN_DENY_PREFIX = "zt:deny:";

    private final String token;          // 可為 null / 空白
    private final String adminUserId;    // DM 對象；可為空
    private final String fallbackChannelId; // DM 失敗後備頻道；可為空
    private final Logger log;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeouts;

    private volatile JDA jda;
    private volatile boolean ready;

    DiscordNotifier(String token, String adminUserId, String fallbackChannelId, Logger log) {
        this.token = token == null ? "" : token.trim();
        this.adminUserId = adminUserId == null ? "" : adminUserId.trim();
        this.fallbackChannelId = fallbackChannelId == null ? "" : fallbackChannelId.trim();
        this.log = log;
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "zerotrust-discord-timeout");
            t.setDaemon(true);
            return t;
        });
        exec.setRemoveOnCancelPolicy(true);
        this.timeouts = exec;
    }

    /** 是否設定了 Token（即「打算」啟用 Discord）。 */
    boolean hasToken() {
        return !token.isEmpty();
    }

    /**
     * 非同步啟動 JDA。應由 {@code onEnable} 在主流程之外呼叫（建立連線可能耗時）。
     * 無 token 則直接返回（選項 B 停用）。
     */
    void start() {
        if (token.isEmpty()) {
            log.info("未設定 DISCORD_BOT_TOKEN——選項 B（Discord 帶外驗證）停用，僅記錄至 console。");
            return;
        }
        try {
            JDABuilder builder = JDABuilder.createLight(token, GatewayIntent.DIRECT_MESSAGES);
            builder.addEventListeners(new Listener());
            // build() 不阻塞；連線完成由 ReadyEvent 標記 ready。
            this.jda = builder.build();
            log.info("Discord Bot 連線中（選項 B）...");
        } catch (Throwable t) {
            // 包含無效 token、網路問題等：降級，選項 B 不可用。
            this.jda = null;
            this.ready = false;
            log.warning("Discord Bot 啟動失敗，選項 B 停用：" + safe(t));
        }
    }

    @Override
    public boolean isAvailable() {
        JDA j = this.jda;
        return ready && j != null && j.getStatus() == JDA.Status.CONNECTED;
    }

    @Override
    public void notice(String message) {
        if (message == null) {
            return;
        }
        log.info("[ZeroTrust 通知] " + message);
        postToFallbackChannel("ℹ️ " + message);
    }

    @Override
    public void alert(AlertLevel level, String message) {
        AlertLevel lvl = level == null ? AlertLevel.MEDIUM : level;
        String text = "[ZeroTrust 警報/" + lvl + "] " + (message == null ? "" : message);
        if (lvl == AlertLevel.EMERGENCY) {
            log.severe(text);
        } else {
            log.warning(text);
        }
        // 警報盡力同時發 DM（emergency 不受冷卻；本層不做冷卻，冷卻由核心控管）。
        String emoji = lvl == AlertLevel.EMERGENCY ? "🚨" : "⚠️";
        String body = emoji + " " + (message == null ? "" : message);
        boolean dmSent = sendDirectMessage(body);
        if (!dmSent) {
            postToFallbackChannel(body);
        }
    }

    @Override
    public CompletableFuture<ConfirmResult> requestLoginConfirmation(String playerName, UUID playerUuid, Duration ttl) {
        CompletableFuture<ConfirmResult> future = new CompletableFuture<>();
        if (!isAvailable() || adminUserId.isEmpty()) {
            future.complete(ConfirmResult.SEND_FAILED);
            return future;
        }

        final String token1 = UUID.randomUUID().toString().replace("-", "");
        final long ttlMillis = ttl == null ? 300_000L : Math.max(1_000L, ttl.toMillis());

        Pending p = new Pending(future);
        pending.put(token1, p);

        MessageEmbed embed = buildLoginEmbed(playerName, ttl);
        Button confirm = Button.success(BTN_CONFIRM_PREFIX + token1, "✅ 確認是我");
        Button deny = Button.danger(BTN_DENY_PREFIX + token1, "❌ 不是我");

        // 嘗試 DM；失敗則退回後備頻道；都失敗 → SEND_FAILED。
        try {
            jda.openPrivateChannelById(adminUserId).queue(
                    channel -> channel.sendMessageEmbeds(embed)
                            .setActionRow(confirm, deny)
                            .queue(
                                    ok -> scheduleTimeout(token1, ttlMillis),
                                    err -> fallbackConfirm(token1, embed, confirm, deny, ttlMillis, err)),
                    err -> fallbackConfirm(token1, embed, confirm, deny, ttlMillis, err));
        } catch (Throwable t) {
            fallbackConfirm(token1, embed, confirm, deny, ttlMillis, t);
        }
        return future;
    }

    /** 關閉 JDA 與逾時排程（由 onDisable 呼叫）。 */
    void shutdown() {
        // 解除所有等待中的 future（避免外部一直等待）。
        for (Map.Entry<String, Pending> e : pending.entrySet()) {
            e.getValue().future.complete(ConfirmResult.SEND_FAILED);
        }
        pending.clear();
        timeouts.shutdownNow();
        JDA j = this.jda;
        if (j != null) {
            try {
                j.shutdown();
            } catch (Throwable ignored) {
                // 盡力關閉。
            }
        }
        this.ready = false;
    }

    // ── 內部 ────────────────────────────────────────────────

    private MessageEmbed buildLoginEmbed(String playerName, Duration ttl) {
        long minutes = ttl == null ? 5 : Math.max(1, ttl.toMinutes());
        return new EmbedBuilder()
                .setTitle("⚠️ 管理員登入請求")
                .setColor(new Color(0xE67E22))
                .addField("玩家", playerName == null ? "(未知)" : playerName, false)
                .addField("時間", TS.format(Instant.now()), false)
                .setFooter("此請求將於約 " + minutes + " 分鐘後過期，請確認是否為本人操作。")
                .build();
    }

    private void fallbackConfirm(String token1, MessageEmbed embed, Button confirm, Button deny,
                                 long ttlMillis, Throwable dmError) {
        log.warning("Discord DM 發送失敗，嘗試後備頻道：" + safe(dmError));
        if (fallbackChannelId.isEmpty()) {
            failConfirm(token1);
            return;
        }
        try {
            MessageChannel ch = resolveFallbackChannel();
            if (ch == null) {
                failConfirm(token1);
                return;
            }
            ch.sendMessageEmbeds(embed)
                    .setActionRow(confirm, deny)
                    .queue(
                            ok -> scheduleTimeout(token1, ttlMillis),
                            err -> {
                                log.warning("Discord 後備頻道發送亦失敗：" + safe(err));
                                failConfirm(token1);
                            });
        } catch (Throwable t) {
            log.warning("Discord 後備頻道發送異常：" + safe(t));
            failConfirm(token1);
        }
    }

    private void failConfirm(String token1) {
        Pending p = pending.remove(token1);
        if (p != null) {
            p.cancelTimeout();
            p.future.complete(ConfirmResult.SEND_FAILED);
        }
    }

    private void scheduleTimeout(String token1, long ttlMillis) {
        Pending p = pending.get(token1);
        if (p == null) {
            return; // 已被回應 / 移除。
        }
        ScheduledFuture<?> handle = timeouts.schedule(() -> {
            Pending pp = pending.remove(token1);
            if (pp != null) {
                pp.future.complete(ConfirmResult.TIMEOUT);
            }
        }, ttlMillis, TimeUnit.MILLISECONDS);
        p.timeout = handle;
    }

    /** 嘗試 DM 純文字（用於 alert）。回傳是否「已提交」DM 請求（非保證送達）。 */
    private boolean sendDirectMessage(String text) {
        if (!isAvailable() || adminUserId.isEmpty()) {
            return false;
        }
        try {
            jda.openPrivateChannelById(adminUserId).queue(
                    channel -> channel.sendMessage(text).queue(null, err ->
                            log.fine("Discord DM 警報送達失敗：" + safe(err))),
                    err -> log.fine("Discord 開啟 DM 失敗：" + safe(err)));
            return true;
        } catch (Throwable t) {
            log.fine("Discord DM 警報異常：" + safe(t));
            return false;
        }
    }

    private void postToFallbackChannel(String text) {
        if (!isAvailable() || fallbackChannelId.isEmpty()) {
            return;
        }
        try {
            MessageChannel ch = resolveFallbackChannel();
            if (ch != null) {
                ch.sendMessage(text).queue(null, err -> log.fine("Discord 頻道訊息送達失敗：" + safe(err)));
            }
        } catch (Throwable t) {
            log.fine("Discord 後備頻道訊息異常：" + safe(t));
        }
    }

    private MessageChannel resolveFallbackChannel() {
        JDA j = this.jda;
        if (j == null || fallbackChannelId.isEmpty()) {
            return null;
        }
        try {
            // 後備頻道通常為文字頻道。
            MessageChannel ch = j.getTextChannelById(fallbackChannelId);
            return ch;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 不洩漏堆疊細節，僅取訊息與類別名（堆疊可能含環境資訊）。 */
    private static String safe(Throwable t) {
        if (t == null) {
            return "(unknown)";
        }
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    /** 等待中的確認請求。 */
    private static final class Pending {
        final CompletableFuture<ConfirmResult> future;
        volatile ScheduledFuture<?> timeout;

        Pending(CompletableFuture<ConfirmResult> future) {
            this.future = future;
        }

        void cancelTimeout() {
            ScheduledFuture<?> h = timeout;
            if (h != null) {
                h.cancel(false);
            }
        }
    }

    /** JDA 事件監聽：連線就緒標記 + 按鈕互動 → 完成對應 future。 */
    private final class Listener extends ListenerAdapter {

        @Override
        public void onReady(ReadyEvent event) {
            ready = true;
            log.info("Discord Bot 連線成功（選項 B 可用）。");
        }

        @Override
        public void onButtonInteraction(ButtonInteractionEvent event) {
            String id = event.getComponentId();
            final String token1;
            final ConfirmResult outcome;
            if (id.startsWith(BTN_CONFIRM_PREFIX)) {
                token1 = id.substring(BTN_CONFIRM_PREFIX.length());
                outcome = ConfirmResult.CONFIRMED;
            } else if (id.startsWith(BTN_DENY_PREFIX)) {
                token1 = id.substring(BTN_DENY_PREFIX.length());
                outcome = ConfirmResult.DENIED;
            } else {
                return; // 非本系統按鈕。
            }

            // 安全：只接受設定的管理員本人點擊（後備頻道情境下尤其重要）。
            if (!adminUserId.isEmpty() && !adminUserId.equals(event.getUser().getId())) {
                event.reply("你無權回應此請求。").setEphemeral(true).queue(null, e -> {});
                return;
            }

            Pending p = pending.remove(token1);
            if (p == null) {
                // 已逾時 / 已處理 / 重啟後殘留。
                event.reply("此請求已過期或已處理。").setEphemeral(true).queue(null, e -> {});
                return;
            }
            p.cancelTimeout();
            p.future.complete(outcome);

            String reply = outcome == ConfirmResult.CONFIRMED
                    ? "✅ 已確認，正在解鎖管理員權限。"
                    : "❌ 已標記為非本人操作，已觸發緊急警報，建議立即變更帳號密碼。";
            // 以 ephemeral 回覆完成互動 ack；再停用原訊息按鈕避免重複點擊（獨立的 REST 編輯）。
            event.reply(reply).setEphemeral(true).queue(null, e -> {});
            event.getMessage().editMessageComponents().queue(null, e -> {});
        }
    }
}

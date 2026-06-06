package com.chococar.zerotrust.forge;

import com.chococar.zerotrust.platform.ScheduledTask;
import com.chococar.zerotrust.platform.Scheduler;

import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * {@link Scheduler} 的 Forge 實作（計劃 2.3）。

 * <h2>執行緒模型</h2>
 * 以單執行緒 {@link ScheduledExecutorService}（daemon）計時；計時觸發時把任務本體
 * <b>重新派送至伺服器主執行緒</b>（{@link MinecraftServer#execute(Runnable)}），確保核心的清理 /
 * 逾時邏輯（會操作玩家、權限）在主執行緒執行，與 Paper 的 {@code BukkitScheduler.runTask} 等價。
 *
 * <p>核心會在建構時呼叫 {@code scheduleRepeating(1s, 1s, ...)}（週期清理）並於選項 A 用
 * {@code scheduleOnce(timeout, ...)}。故本排程器需在引擎建立<b>之前</b>就持有 {@link MinecraftServer}。
 *
 * <p>{@link ScheduledTask#cancel()} 會取消底層 future；若任務已派送至主執行緒佇列，則以
 * {@code cancelled} 旗標於主執行緒執行前最後一刻短路，避免關閉後仍跑（fail-closed 友善）。
 */
final class ForgeScheduler implements Scheduler {

    private final MinecraftServer server;
    private final Logger log;
    private final ScheduledExecutorService timer;

    ForgeScheduler(MinecraftServer server, Logger log) {
        this.server = Objects.requireNonNull(server, "server");
        this.log = Objects.requireNonNull(log, "log");
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "zerotrust-forge-scheduler");
            t.setDaemon(true);
            return t;
        });
        exec.setRemoveOnCancelPolicy(true);
        this.timer = exec;
    }

    @Override
    public ScheduledTask scheduleOnce(Duration delay, Runnable task) {
        Objects.requireNonNull(task, "task");
        Handle handle = new Handle();
        long millis = Math.max(0L, delay == null ? 0L : delay.toMillis());
        ScheduledFuture<?> future = timer.schedule(
                () -> dispatch(handle, task), millis, TimeUnit.MILLISECONDS);
        handle.bind(future);
        return handle;
    }

    @Override
    public ScheduledTask scheduleRepeating(Duration initialDelay, Duration period, Runnable task) {
        Objects.requireNonNull(task, "task");
        Handle handle = new Handle();
        long initial = Math.max(0L, initialDelay == null ? 0L : initialDelay.toMillis());
        long periodMs = Math.max(1L, period == null ? 1L : period.toMillis());
        ScheduledFuture<?> future = timer.scheduleAtFixedRate(
                () -> dispatch(handle, task), initial, periodMs, TimeUnit.MILLISECONDS);
        handle.bind(future);
        return handle;
    }

    /** 計時執行緒：把任務本體丟回主執行緒，並在主執行緒執行前再次檢查取消旗標。 */
    private void dispatch(Handle handle, Runnable task) {
        if (handle.isCancelled() || server.isStopped()) {
            return;
        }
        try {
            server.execute(() -> {
                if (handle.isCancelled()) {
                    return;
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    log.warning("ZeroTrust 排程任務於主執行緒拋出例外：" + t);
                }
            });
        } catch (RuntimeException e) {
            // 伺服器停止後 execute 可能拒絕；忽略（任務本應停止）。
            log.fine("ZeroTrust 排程派送至主執行緒失敗（伺服器可能停止中）：" + e.getMessage());
        }
    }

    /** 關閉計時器（伺服器停止 / 引擎 shutdown 時呼叫）。 */
    void shutdown() {
        timer.shutdownNow();
    }

    /** 可取消句柄；以旗標 + future.cancel 雙重保險。 */
    private static final class Handle implements ScheduledTask {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> future;

        void bind(ScheduledFuture<?> f) {
            this.future = f;
            // 若在 bind 前已被取消（極端競態），立即取消 future。
            if (cancelled.get() && f != null) {
                f.cancel(false);
            }
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            ScheduledFuture<?> f = future;
            if (f != null) {
                f.cancel(false);
            }
        }
    }
}

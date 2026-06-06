package com.chococar.zerotrust.fabric;

import com.chococar.zerotrust.platform.ScheduledTask;
import com.chococar.zerotrust.platform.Scheduler;

import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * {@link Scheduler} 的 Fabric 實作。
 *
 * <h2>執行緒模型</h2>
 * 以背景 {@link ScheduledExecutorService} 計時，但任務本體一律透過
 * {@link MinecraftServer#execute(Runnable)} 排回<b>伺服器主執行緒</b>後才執行——因為引擎的排程回呼
 * （選項 A 逾時、Session/Nonce 週期清理）往往會再呼叫 {@link com.chococar.zerotrust.platform.PlatformAdapter}
 * 去操作玩家 / 權限，而那些操作必須在主執行緒上（與 Paper 的 {@code PaperScheduler} 同策略）。
 *
 * <p>{@link MinecraftServer#execute} 會將 Runnable 排入伺服器的任務佇列，於下一個可執行點在主執行緒跑。
 * 若伺服器正在關閉而拒收任務，例外會被吞掉並記錄，不致拖垮排程器。
 */
final class FabricScheduler implements Scheduler {

    private final MinecraftServer server;
    private final Logger log;
    private final ScheduledExecutorService timer;

    FabricScheduler(MinecraftServer server, Logger log) {
        this.server = Objects.requireNonNull(server, "server");
        this.log = Objects.requireNonNull(log, "log");
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "zerotrust-fabric-scheduler");
            t.setDaemon(true);
            return t;
        });
        exec.setRemoveOnCancelPolicy(true);
        this.timer = exec;
    }

    @Override
    public ScheduledTask scheduleOnce(Duration delay, Runnable task) {
        Objects.requireNonNull(task, "task");
        long millis = toMillis(delay);
        ScheduledFuture<?> handle = timer.schedule(() -> runOnServer(task), millis, TimeUnit.MILLISECONDS);
        return new FutureTask(handle);
    }

    @Override
    public ScheduledTask scheduleRepeating(Duration initialDelay, Duration period, Runnable task) {
        Objects.requireNonNull(task, "task");
        long initialMillis = toMillis(initialDelay);
        long periodMillis = Math.max(1L, toMillis(period));
        ScheduledFuture<?> handle = timer.scheduleAtFixedRate(
                () -> runOnServer(task), initialMillis, periodMillis, TimeUnit.MILLISECONDS);
        return new FutureTask(handle);
    }

    /** 關閉計時執行緒（伺服器停止時呼叫）。 */
    void shutdown() {
        timer.shutdownNow();
    }

    // ── 內部 ────────────────────────────────────────────────

    /** 將任務排回主執行緒；於其上包一層例外保護，避免回呼例外冒泡。 */
    private void runOnServer(Runnable task) {
        try {
            // 伺服器關閉後 execute 可能拋例外或永不執行；以保護包覆。
            server.execute(() -> safeRun(task));
        } catch (Throwable t) {
            // 伺服器已停止或佇列已關閉：記錄即可（fail-closed 不依賴此回呼成功）。
            log.fine("ZeroTrust 排程任務無法排入主執行緒（伺服器可能正在關閉）：" + t);
        }
    }

    private void safeRun(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            log.severe("ZeroTrust 排程任務拋出例外：" + t);
        }
    }

    private static long toMillis(Duration d) {
        if (d == null || d.isNegative() || d.isZero()) {
            return 1L;
        }
        return Math.max(1L, d.toMillis());
    }

    private static final class FutureTask implements ScheduledTask {
        private final ScheduledFuture<?> handle;

        FutureTask(ScheduledFuture<?> handle) {
            this.handle = handle;
        }

        @Override
        public void cancel() {
            if (handle != null) {
                handle.cancel(false);
            }
        }
    }
}

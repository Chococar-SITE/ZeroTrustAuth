package com.chococar.zerotrust.paper;

import com.chococar.zerotrust.platform.ScheduledTask;
import com.chococar.zerotrust.platform.Scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link Scheduler} 的 Bukkit 實作。
 *
 * <p>一律以<b>同步（主執行緒）</b>任務排程，因為引擎的排程回呼（選項 A 逾時、
 * Session/Nonce 清理）往往會再次呼叫 {@link com.chococar.zerotrust.platform.PlatformAdapter}
 * 去操作玩家 / 權限，而那些操作必須在主執行緒上。{@link PaperPlatformAdapter} 本身亦會
 * 再次 marshal，但讓回呼直接落在主執行緒可避免不必要的跳轉並確保時序。
 *
 * <p>Bukkit 以 tick 計時（20 tick/秒）。延遲換算為 {@code max(1, round(seconds*20))}，
 * 確保至少 1 tick（0 或負延遲在 Bukkit 會拋例外）。
 */
final class PaperScheduler implements Scheduler {

    private final Plugin plugin;

    PaperScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public ScheduledTask scheduleOnce(Duration delay, Runnable task) {
        Objects.requireNonNull(task, "task");
        long ticks = toTicks(delay);
        BukkitTask handle = Bukkit.getScheduler().runTaskLater(plugin, wrap(task), ticks);
        return new BukkitScheduledTask(handle);
    }

    @Override
    public ScheduledTask scheduleRepeating(Duration initialDelay, Duration period, Runnable task) {
        Objects.requireNonNull(task, "task");
        long initialTicks = toTicks(initialDelay);
        long periodTicks = toTicks(period);
        BukkitTask handle = Bukkit.getScheduler().runTaskTimer(plugin, wrap(task), initialTicks, periodTicks);
        return new BukkitScheduledTask(handle);
    }

    /** 包住任務，避免回呼例外冒泡到 Bukkit 排程器（會吵雜地刷錯誤並可能中止重複任務）。 */
    private Runnable wrap(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                plugin.getLogger().severe("ZeroTrust 排程任務拋出例外：" + t);
            }
        };
    }

    private static long toTicks(Duration d) {
        if (d == null || d.isNegative() || d.isZero()) {
            return 1L;
        }
        // 以毫秒換算再四捨五入到 tick，避免小於 1 秒的延遲被截為 0。
        long ticks = Math.round(d.toMillis() / 50.0);
        return Math.max(1L, ticks);
    }

    private static final class BukkitScheduledTask implements ScheduledTask {
        private final BukkitTask handle;

        BukkitScheduledTask(BukkitTask handle) {
            this.handle = handle;
        }

        @Override
        public void cancel() {
            if (handle != null) {
                try {
                    handle.cancel();
                } catch (RuntimeException ignored) {
                    // 任務可能已執行完畢或已取消。
                }
            }
        }
    }
}

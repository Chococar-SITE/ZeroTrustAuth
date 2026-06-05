package com.chococar.zerotrust.support;

import com.chococar.zerotrust.platform.ScheduledTask;
import com.chococar.zerotrust.platform.Scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 測試用 {@link Scheduler}：不真正排程，改由測試手動觸發。
 *
 * <ul>
 *   <li>{@link #fireOnce()}：執行所有目前未取消的一次性任務（執行後移除）。</li>
 *   <li>{@link #tickRepeating()}：執行所有目前未取消的週期任務一次（保留）。</li>
 *   <li>取消後的任務不再被觸發。</li>
 * </ul>
 */
public final class FakeScheduler implements Scheduler {

    private final List<Task> onceTasks = new CopyOnWriteArrayList<>();
    private final List<Task> repeatingTasks = new CopyOnWriteArrayList<>();

    private final class Task implements ScheduledTask {
        final Runnable runnable;
        volatile boolean cancelled = false;
        final boolean repeating;

        Task(Runnable runnable, boolean repeating) {
            this.runnable = runnable;
            this.repeating = repeating;
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (repeating) repeatingTasks.remove(this);
            else onceTasks.remove(this);
        }
    }

    @Override
    public ScheduledTask scheduleOnce(Duration delay, Runnable task) {
        Task t = new Task(task, false);
        onceTasks.add(t);
        return t;
    }

    @Override
    public ScheduledTask scheduleRepeating(Duration initialDelay, Duration period, Runnable task) {
        Task t = new Task(task, true);
        repeatingTasks.add(t);
        return t;
    }

    /** 觸發所有未取消的一次性任務一次，執行後從佇列移除。 */
    public void fireOnce() {
        List<Task> snapshot = new ArrayList<>(onceTasks);
        for (Task t : snapshot) {
            if (!t.cancelled) {
                onceTasks.remove(t);
                t.runnable.run();
            }
        }
    }

    /** 觸發所有未取消的週期任務各一次（保留以供再次觸發）。 */
    public void tickRepeating() {
        List<Task> snapshot = new ArrayList<>(repeatingTasks);
        for (Task t : snapshot) {
            if (!t.cancelled) {
                t.runnable.run();
            }
        }
    }

    public int pendingOnceCount() {
        int n = 0;
        for (Task t : onceTasks) if (!t.cancelled) n++;
        return n;
    }

    public int repeatingCount() {
        int n = 0;
        for (Task t : repeatingTasks) if (!t.cancelled) n++;
        return n;
    }
}

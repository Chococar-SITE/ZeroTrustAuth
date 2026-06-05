package com.chococar.zerotrust.platform;

import java.time.Duration;

/**
 * 排程抽象。核心用於選項 A 逾時（10 秒）、Session/Nonce 清理等。
 * 平台以其原生排程器實作；測試可用可手動推進的假實作。
 */
public interface Scheduler {

    /** 延遲後執行一次。 */
    ScheduledTask scheduleOnce(Duration delay, Runnable task);

    /** 固定週期重複執行（用於過期清理）。 */
    ScheduledTask scheduleRepeating(Duration initialDelay, Duration period, Runnable task);
}

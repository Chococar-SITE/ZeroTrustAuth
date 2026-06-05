package com.chococar.zerotrust;

import com.chococar.zerotrust.audit.LogSink;
import com.chococar.zerotrust.config.ZeroTrustConfig;
import com.chococar.zerotrust.notify.Notifier;
import com.chococar.zerotrust.platform.KeyRepository;
import com.chococar.zerotrust.platform.PlatformAdapter;
import com.chococar.zerotrust.platform.Scheduler;

import java.time.Clock;
import java.util.Objects;

/**
 * 引擎的單一接線點：平台層蒐集所有依賴並傳給核心引擎建構子
 * （{@code new ZeroTrustCore(EngineContext)}）。
 *
 * <p>秘密以位元組陣列注入（來自環境變數），不經設定檔：
 * {@code ipHmacSecret} 對應 {@code IP_HMAC_SECRET}（計劃 6.2）。
 */
public final class EngineContext {

    private final ZeroTrustConfig config;
    private final PlatformAdapter adapter;
    private final Scheduler scheduler;
    private final Notifier notifier;
    private final LogSink logSink;
    private final KeyRepository keyRepository;
    private final byte[] ipHmacSecret;
    private final Clock clock;

    private EngineContext(Builder b) {
        this.config = Objects.requireNonNull(b.config, "config");
        this.adapter = Objects.requireNonNull(b.adapter, "adapter");
        this.scheduler = Objects.requireNonNull(b.scheduler, "scheduler");
        this.notifier = Objects.requireNonNull(b.notifier, "notifier");
        this.logSink = Objects.requireNonNull(b.logSink, "logSink");
        this.keyRepository = Objects.requireNonNull(b.keyRepository, "keyRepository");
        this.ipHmacSecret = Objects.requireNonNull(b.ipHmacSecret, "ipHmacSecret").clone();
        this.clock = b.clock != null ? b.clock : Clock.systemUTC();
    }

    public ZeroTrustConfig config() { return config; }
    public PlatformAdapter adapter() { return adapter; }
    public Scheduler scheduler() { return scheduler; }
    public Notifier notifier() { return notifier; }
    public LogSink logSink() { return logSink; }
    public KeyRepository keyRepository() { return keyRepository; }
    public byte[] ipHmacSecret() { return ipHmacSecret.clone(); }
    public Clock clock() { return clock; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private ZeroTrustConfig config;
        private PlatformAdapter adapter;
        private Scheduler scheduler;
        private Notifier notifier;
        private LogSink logSink;
        private KeyRepository keyRepository;
        private byte[] ipHmacSecret;
        private Clock clock;

        public Builder config(ZeroTrustConfig v) { this.config = v; return this; }
        public Builder adapter(PlatformAdapter v) { this.adapter = v; return this; }
        public Builder scheduler(Scheduler v) { this.scheduler = v; return this; }
        public Builder notifier(Notifier v) { this.notifier = v; return this; }
        public Builder logSink(LogSink v) { this.logSink = v; return this; }
        public Builder keyRepository(KeyRepository v) { this.keyRepository = v; return this; }
        public Builder ipHmacSecret(byte[] v) { this.ipHmacSecret = v; return this; }
        /** 可選；省略則用 {@link Clock#systemUTC()}。測試可注入固定時鐘。 */
        public Builder clock(Clock v) { this.clock = v; return this; }

        public EngineContext build() { return new EngineContext(this); }
    }
}

package com.chococar.zerotrust.support;

import com.chococar.zerotrust.EngineContext;
import com.chococar.zerotrust.ZeroTrustCore;
import com.chococar.zerotrust.config.ZeroTrustConfig;
import com.chococar.zerotrust.platform.StoredKey;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 整合測試接線：以所有假實作組裝 {@link EngineContext}，並<b>延遲</b>建構 {@link ZeroTrustCore}
 * （首次存取 {@link #core()} 時才建構），以便在建構引擎前先植入種子金鑰
 * （{@link com.chococar.zerotrust.auth.PublicKeyStore} 於建構時 {@code loadAll}）。
 *
 * <p>每個 Harness 僅建構一個引擎，避免多個引擎共用 {@link FakeScheduler} 造成的任務交叉。
 */
public final class TestHarness {

    public final MutableClock clock = MutableClock.atEpoch();
    public final FakePlatformAdapter adapter = new FakePlatformAdapter();
    public final FakeScheduler scheduler = new FakeScheduler();
    public final FakeNotifier notifier = new FakeNotifier();
    public final InMemoryLogSink logSink = new InMemoryLogSink();
    public final InMemoryKeyRepository keyRepo = new InMemoryKeyRepository();
    public final byte[] ipSecret = "test-ip-hmac-secret-0123456789".getBytes();

    public final ZeroTrustConfig config;
    public final EngineContext ctx;

    private ZeroTrustCore core;

    public TestHarness() {
        this(b -> {});
    }

    public TestHarness(Consumer<ZeroTrustConfig.Builder> configCustomizer) {
        ZeroTrustConfig.Builder cb = ZeroTrustConfig.builder();
        configCustomizer.accept(cb);
        this.config = cb.build();
        this.ctx = EngineContext.builder()
                .config(config)
                .adapter(adapter)
                .scheduler(scheduler)
                .notifier(notifier)
                .logSink(logSink)
                .keyRepository(keyRepo)
                .ipHmacSecret(ipSecret)
                .clock(clock)
                .build();
    }

    /** 植入種子金鑰（必須在首次 {@link #core()} 之前呼叫）。 */
    public TestHarness seedKey(UUID uuid, String label, String base64PublicKey) {
        keyRepo.seed(uuid, new StoredKey(label, base64PublicKey, "generated", null));
        return this;
    }

    /** 取得（首次呼叫時建構）引擎。建構後即啟動週期清理任務。 */
    public ZeroTrustCore core() {
        if (core == null) {
            core = new ZeroTrustCore(ctx);
        }
        return core;
    }
}

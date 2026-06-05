package com.chococar.zerotrust.auth;

import com.chococar.zerotrust.EngineContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 啟動自檢（計劃 6.3）。任一硬性檢查失敗即應進入「安全模式」：拒絕所有管理員授權。
 * 軟性檢查（如 Discord 連線）失敗僅記為警告（選項 B 視為停用）。
 *
 * <p>本檢查僅依賴跨模組契約，與平台無關；平台層在 {@code onEnable} 時呼叫。
 */
public final class SelfTest {

    private SelfTest() {}

    /** 自檢報告。 */
    public static final class Report {
        private final List<String> failures;
        private final List<String> warnings;

        Report(List<String> failures, List<String> warnings) {
            this.failures = Collections.unmodifiableList(failures);
            this.warnings = Collections.unmodifiableList(warnings);
        }

        /** 是否通過（無硬性失敗）。 */
        public boolean passed() { return failures.isEmpty(); }
        public List<String> failures() { return failures; }
        public List<String> warnings() { return warnings; }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(passed() ? "SELF-TEST PASSED" : "SELF-TEST FAILED");
            if (!failures.isEmpty()) sb.append(" failures=").append(failures);
            if (!warnings.isEmpty()) sb.append(" warnings=").append(warnings);
            return sb.toString();
        }
    }

    /**
     * 執行自檢。
     *
     * @param ctx                     引擎接線
     * @param permissionBackendLoaded 權限後端（如 LuckPerms）是否已載入（平台判斷）
     */
    public static Report run(EngineContext ctx, boolean permissionBackendLoaded) {
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // IP_HMAC_SECRET（環境變數）必須存在且具足夠長度。
        if (ctx.ipHmacSecret() == null || ctx.ipHmacSecret().length < 16) {
            failures.add("IP_HMAC_SECRET 缺失或過短（需 >= 16 bytes）");
        }
        // 領域分隔前綴必須設定（計劃 3.2 / 6.3）。
        String domain = ctx.config().signatureDomain();
        if (domain == null || domain.isBlank()) {
            failures.add("signature_domain 未設定");
        }
        // fail_closed 不可關閉（計劃 1.2 / 7.3）。
        if (!ctx.config().failClosed()) {
            failures.add("fail_closed 必須啟用");
        }
        // PublicKeyStore 來源可讀（計劃 6.3）。
        try {
            ctx.keyRepository().loadAll();
        } catch (RuntimeException e) {
            failures.add("公鑰儲存無法讀取：" + e.getMessage());
        }
        // 權限後端已載入。
        if (!permissionBackendLoaded) {
            failures.add("權限後端未載入");
        }
        // Discord 連線（軟性）。
        if (!ctx.notifier().isAvailable()) {
            warnings.add("Discord 不可用——選項 B 停用");
        }

        return new Report(failures, warnings);
    }
}

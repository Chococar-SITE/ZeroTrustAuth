# CLAUDE.md

Minecraft 伺服器「零信任身份驗證」系統。在 Mojang 帳號驗證之上，為**管理員**加一道**裝置層**驗證。核心：**Never Trust, Always Verify**。完整設計見 `docs/ZeroTrust_2FA_Plan.md`（本檔為精簡工作參考，細節再查原文）。

## 當前狀態
**Core（Phase 1）＋ Paper 外掛（Phase 2）＋ Discord 選項 B（Phase 3）已實作並測試**：`:core` 110 項單元測試（純 JDK，本地可跑）＋ CI 啟動**真實 Paper 伺服器**的執行測試（`.github/workflows/mc-server-test.yml`）。Fabric／Forge／NeoForge 與客戶端 Mod 尚未實作（Phase 4–5）。
- 已實作模組：`:core`（`ZeroTrustCore` 等）、`:platform-paper`（`ZeroTrustPlugin` 等）。
- 沙箱網路限制：Maven Central／Gradle Portal 可達，但 paper-api／Fabric／Forge／NeoForge／Mojang maven 被封鎖 → `:core` 可本地建置測試，`:platform-paper` 僅能在 CI（開放網路）建置。
- 一般玩家零感知；只有管理員帳號登入後被凍結，驗證通過才解鎖權限。

## 建置 / 測試（實際指令）
`./gradlew :core:test`（核心 110 測試，本地）· `./gradlew :platform-paper:shadowJar`（外掛 jar，CI）· `./gradlew build`（全建置＋測試）· `ci/mc-server-test.sh`（真實 Paper 伺服器執行測試，需連網）。

## 技術棧
| 項目 | 版本 |
|--|--|
| Java | 17+（NeoForge 模組需 21+）|
| Gradle | 8.x（多模組）|
| Architectury | 跨平台 Mod 共用核心 |
| LuckPerms API | 5.4+（Paper 動態權限）|
| fabric-permissions-api | 0.3+ |
| JDA | 5.x（Discord Bot）|
| Bouncy Castle | 1.78+（OpenSSH 私鑰解析）|
| Guava Cache | Nonce/Token 自動過期 |

## 模組結構
```
core/auth/    ChallengeManager · SignatureVerifier · AdminSession · PublicKeyStore
core/notify/  DiscordNotifier · OutOfBandChallenge
client-mod/   KeyManager · SignatureResponder · KeyUploadCommand（選項 A 客戶端）
platform-{paper,fabric,forge,neoforge}/   各平台適配，皆實作 PlatformAdapter
```
`PlatformAdapter`：freeze/unfreezePlayer、grant/revokeAdminPerm、kickPlayer、sendMessage、notifyConsole、isAdminAccount。**核心邏輯不得依賴任何平台 API。**


## 🔒 不可違反的安全不變式（本專案「原意」核心）
1. **Fail-Closed**：任何不確定（設定損毀、依賴缺失、後端不可用、元件崩潰）一律**拒絕授權**，絕不 fail-open。`fail_closed` 不可關閉。
2. **權限不持久化**：Paper 用 LuckPerms **transient node**（`user.transientData()`），**絕不**寫一般 node（會進 DB、重啟殘留）。登出／到期／撤銷／`onDisable` 皆主動撤回。
3. **剝奪原版 OP**：登入先移除 `ops.json` OP（否則直接繞過本系統），驗證後才受控恢復，登出／撤銷再移除。理想：管理員完全不用原版 OP。
4. **領域分隔（簽名預言機防護）**：客戶端**絕不**簽裸 Nonce。固定簽 `signature_domain + nonce`（預設 `MC-ZEROTRUST-AUTH-v1:`，連接後 SHA-512），伺服器驗證套同一前綴。前綴帶版本號供演算法升級（crypto agility，未來 `-v2:` 過渡期雙版並收）。
5. **Nonce**：32 bytes、30 秒過期、用後即廢、綁 UUID **且綁該次連線/Session ID**（防挪用至同帳號其他連線）。
6. **Enrollment（TOFU）**：首次上傳公鑰必須帶**主控台**產生的一次性註冊碼（128-bit、10 分鐘、速率限制、用後即廢）。`enroll`＝用主控台建立信任；`rotate`＝用既有金鑰證明身份換鑰（免註冊碼）。緊急撤銷僅主控台。
7. **公鑰驗證**：上傳時須確認為合法 Ed25519、結構/長度正確；拒絕 RSA/ECDSA/畸形並記錄（防金鑰類型混淆）。
8. **秘密只走環境變數**：`DISCORD_BOT_TOKEN`、`IP_HMAC_SECRET` 一律從 env 讀取，**永不**進程式碼、設定檔或 commit。
9. **日誌**：結構化 JSON、不含任何秘密；IP 必用 **HMAC-SHA256＋密鑰鹽**（欄位 `ip_hmac`），**禁用裸 `sha256(ip)`**（IPv4 秒級可還原）。每日輪替、保留 90 天。
10. **撤銷即時生效**：`revoke` 須立刻撤回權限並踢出／降權當前活躍 Session，不能等 TTL。

## 驗證流程（摘要）
管理員連線 → Mojang 驗證 → **立即凍結** → 查有無公鑰。
- **選項 A（有公鑰）**：送 Nonce 封包 → 客戶端加前綴簽名回傳 → 公鑰驗證。10 秒逾時。
- **選項 B（無公鑰／逾時）**：Discord DM 一次性 Token（5 分鐘）→ 管理員點「✅ 確認是我」→ 解鎖；點「❌ 不是我」立即緊急警報。
- 降級由 `allow_fallback` 控制；`false`＝嚴格模式僅選項 A（逾時／無公鑰直接踢出）。**降級是攻擊面**，高安全環境設 false。
- 通過 → transient 授權＋重置 TTL；失敗累計，**≥3 次踢出＋警報**。
- 日誌 `result`：`SUCCESS`/`FAIL`/`DOWNGRADED_A_TO_B`/`FALLBACK_DENIED`/`TRUSTED_RESUME`。

## Session 狀態機
`FROZEN →(通過) VERIFIED →(4h／登出) EXPIRED / REVOKED`。**僅存記憶體**，重啟清空。EXPIRED 可在線 `/authkey verify` 重驗（重新凍結→驗證→恢復，免斷線）。同帳號多重連線＝警報。

## 凍結定義（須在**最早 hook** 套用，避免 TOCTOU 空窗）
移動（僅視角）· 無敵（防死亡/掉落）· 背包鎖定 · 容器禁開 · 方塊禁互動 · 聊天禁止 · 指令僅放行 `/authkey`。凍結期非驗證封包速率超限（預設 20/s）→ 踢出＋警報。

## 指令
| 指令 | 位置 | 用途 |
|--|--|--|
| `/authkey enroll <uuid>` | **主控台** | 產生一次性註冊碼 |
| `/authkey upload <pubkey> <code> [label]` | 遊戲內 | 上傳新公鑰（首次/重設）|
| `/authkey rotate [label]` | 遊戲內 | 既有金鑰換鑰，免註冊碼 |
| `/authkey verify` | 遊戲內 | 在線重新驗證 |
| `/authkey list` | 遊戲內 | 列出自己金鑰/label/last-used |
| `/authkey revoke <uuid> [label]` | **主控台** | 撤銷金鑰＋終止 Session |

## 多金鑰 / 信任裝置
- 每管理員可多把公鑰（label、獨立撤銷、last_used）；任一把成功即通過。
- 信任裝置：選項 A 已驗證裝置登出後 **15 分鐘**內重連，自動簽新 Nonce 即時確認免完整流程；**不延長 TTL**。**純選項 B 不適用**（無金鑰可即時證明身份）。
- 金鑰輪換：舊公鑰標「待淘汰」保留 **24h**，期間新舊皆可驗證。

## Proxy（Velocity / BungeeCord）
- 正版驗證在 **Proxy 層**；後端 `online-mode=false`＋轉發密鑰，**後端必須防火牆只接受 Proxy 連線**，否則 offline 後端＝任意帳號入口。
- Proxy→後端鏈路須隔離（VLAN/私網）或加密，避免 Nonce/簽名被竊聽。
- Session 共享：Redis 或 Proxy 插件 Plugin Message Channel；僅限同一 Proxy 網路。

## Discord
帳號被盜＝選項 B 淪陷 → 要求管理員 Discord 開 2FA。DM 失敗須偵測 → 退回 `fallback_channel_id`＋主控台/日誌警報。通知冷卻 `notify_cooldown_seconds`（預設 60，防 DM 轟炸/通知疲勞）；緊急類（撤銷、「不是我」）不受冷卻。

## 啟動自檢（任一失敗 → 安全模式拒絕所有授權）
PublicKeyStore 可讀、必要 env 存在、權限後端已載入、領域前綴一致 → 皆 Fail-closed。Discord 連線失敗 → 僅標記選項 B 不可用（警告，非全域 fail）。

## 設定鍵（見 `config.example.yml`，秘密走 env）
`settings.{session_ttl_hours=4, max_attempts=3, option_a_timeout_seconds=10, option_b_token_ttl_minutes=5, enrollment_token_ttl_minutes=10, enrollment_max_attempts=5, allow_fallback, freeze_packet_limit_per_second=20, trusted_device_window_minutes=15, strip_vanilla_op, fail_closed}` · `security.signature_domain` · `discord.{admin_discord_id, notify_cooldown_seconds, fallback_channel_id}` · `logging.{format=json, rotation=daily, retention_days=90}`。

## ⚠️ 公開 Repo 守則
- **絕不 commit**：真實 Token／Webhook URL／私鑰／`.env`／真實 UUID 或 Discord ID。範例一律用佔位符。
- 真實 `config.yml`、`*.key/.pem`、`.env` 已列入 `.gitignore`；只提交 `config.example.yml`。
- 推送前注意 gitleaks（CI 會掃）；漏洞回報走 `SECURITY.md` 私密管道，勿開公開 issue。
- 提交訊息／程式碼／任何成品**不得**寫入模型識別碼或內部資訊。

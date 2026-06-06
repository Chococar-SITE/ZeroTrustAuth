# ZeroTrustAuth

> Minecraft 伺服器零信任身份驗證系統 — **Never Trust, Always Verify**
>
> 在 Mojang 帳號驗證之上，為**管理員**加一道「裝置層」驗證：就算帳號被完全盜用，沒有你的裝置或手機，入侵者也拿不到管理員權限。

[![Build](https://github.com/Chococar-SITE/ZeroTrustAuth/actions/workflows/build.yml/badge.svg)](https://github.com/Chococar-SITE/ZeroTrustAuth/actions/workflows/build.yml)
[![MC Server Test](https://github.com/Chococar-SITE/ZeroTrustAuth/actions/workflows/mc-server-test.yml/badge.svg)](https://github.com/Chococar-SITE/ZeroTrustAuth/actions/workflows/mc-server-test.yml)
[![Mods](https://github.com/Chococar-SITE/ZeroTrustAuth/actions/workflows/mods.yml/badge.svg)](https://github.com/Chococar-SITE/ZeroTrustAuth/actions/workflows/mods.yml)
[![Secret Scan](https://github.com/Chococar-SITE/ZeroTrustAuth/actions/workflows/secret-scan.yml/badge.svg)](https://github.com/Chococar-SITE/ZeroTrustAuth/actions/workflows/secret-scan.yml)
![Java](https://img.shields.io/badge/Java-17%2B-orange)
![Platforms](https://img.shields.io/badge/平台-Paper%20%7C%20Fabric%20%7C%20NeoForge%20%7C%20Forge-blue)

> **狀態：全平台可建置** — 核心＋Paper＋Discord（Phase 1–3）已測試（**120 項單元測試** ＋ CI 真實 Paper 伺服器執行測試，現於 **MC 26.1.2**）；Fabric／NeoForge（**26.1**，CI 建置驗證）與 Forge（1.20.1 舊版旗艦）的**伺服器端與客戶端 Mod**（Phase 4–5）皆已實作並於 CI 建置。選項 A（Ed25519 簽名）在三種載入器皆端到端串通。

## 這是什麼

傳統白名單只驗證「帳號」，無法確認登入者是不是帳號的真正主人。即使是正版伺服器，一旦帳號或 Token 被盜，入侵者就能取得與管理員相同的權限。

本系統針對**管理員帳號被盜、Cookie/Token 被竊、陌生環境登入、社交工程**等情境，要求管理員每次登入都通過第二道**裝置層**驗證才解鎖權限。**一般玩家完全零感知**，通過白名單即正常遊玩。

## 特色

- 🔐 **兩種驗證**：選項 A — Ed25519 簽名（私鑰永不離開你的電腦，近似 Passkey）；選項 B — Discord 手機帶外確認。
- 🧊 **登入即凍結**管理員，驗證通過才動態授權；**權限不持久化**，登出／到期立即撤銷。
- 🛡️ **Fail-Closed**：系統一有異常一律拒絕授權，寧可鎖住自己也不開門揖盜。
- 🔁 **多金鑰多裝置**、在線重新驗證、信任裝置快速重連。
- 🌐 **跨平台**：Paper／Fabric／Forge／NeoForge。
- 📣 **Discord 即時警報**：驗證失敗、金鑰撤銷、可疑登入皆通報。

## 運作方式

```
管理員連線 → Mojang 正版驗證 → 立即凍結（禁止一切操作）
        → 查詢是否已登記 Ed25519 公鑰？
           ├─ 有公鑰 → 選項 A：簽名挑戰（10s 逾時）
           └─ 無公鑰 → 選項 B：Discord DM 確認（5min）
        → 驗證通過 → 動態授予管理員權限（Session 4 小時）
        → 登出或到期 → 權限自動撤銷，下次重新驗證
```

- **選項 A**：伺服器發 32-byte Nonce → 客戶端 Mod 加領域前綴後簽名 → 伺服器以公鑰驗證。私鑰不外傳、Nonce 用後即廢（防重放）。
- **選項 B**：Discord Bot 發 DM 一次性 Token → 手機點「✅ 確認是我」即解鎖；點「❌ 不是我」立即觸發緊急警報。

## 架構

三層式設計，核心邏輯與平台無關：

| 層級 | 模組 | 職責 |
|--|--|--|
| 核心層 | `core/auth`、`core/notify` | 簽名驗證、Session 管理、Enrollment、Discord 通知 |
| 平台適配層 | `platform-*` | 事件監聽、玩家凍結、權限授予（各平台實作 `PlatformAdapter`）|
| 客戶端層 | `client-mod` | 私鑰管理、自動簽名回應（選項 A 專用）|

## 支援平台

> **版本分工**：Forge 走舊版線（1.7.10→1.20.1 的 **7 個精選版本**，除 1.20.1 外每受支援大版本取最新小版本；**不含 1.9–1.11、1.13–1.15、1.17**）；NeoForge 走現代線（1.20.1+，每大版本取最新）；Fabric／Paper 涵蓋 1.20.1→26.1。**完整目標支援矩陣見 [SUPPORT.md](SUPPORT.md)**。
>
> 下表為**目前已 CI 建置／測試的旗艦子集**（非完整目標範圍）：

| 平台 | 目前建置版本 | 權限機制 | 狀態 |
|--|--|--|--|
| Paper / Spigot | 26.1.2 | LuckPerms transient（無則 Bukkit attachment）| ✅ 真實伺服器 CI 測試 |
| Fabric | 26.1 | 記憶體 transient 授權 | ✅ 伺服器＋客戶端 CI 建置 |
| NeoForge | 26.1.2 | 記憶體 transient 授權 | ✅ 伺服器＋客戶端 CI 建置 |
| Forge | 1.20.1（舊版旗艦）| 記憶體 transient 授權 | ✅ 伺服器＋客戶端 CI 建置 |

各平台皆實作同一 `PlatformAdapter`，凍結一律在最早 hook 套用（移動/背包/容器/方塊/聊天/無敵，僅放行 `/authkey`）。客戶端 Mod（選項 A 自動簽名）共用 `client-core`（Ed25519＋SSH key 複用）。

## 建置與測試

需 **JDK 25**（Paper 26.1 / 現代線需要）＋ **JDK 21**（核心 toolchain，目標位元碼為 Java 17）；Forge 舊版線用 **JDK 17**。建置使用 **Gradle 9.4 wrapper**（Forge 因 ForgeGradle 6 不相容 Gradle 9，於獨立 job 用 pinned Gradle 8）。

```bash
./gradlew build                      # 全建置 + 測試
./gradlew :core:test                 # 僅核心單元測試（110 項，純 JDK；需 Gradle 9.4 wrapper + JDK，沙箱已無法離線建置）
./gradlew :platform-paper:shadowJar  # 產生 Paper 外掛 jar（已內嵌 JDA 並重定位）
```

產物：`platform-paper/build/libs/platform-paper-*.jar` —— 放入伺服器 `plugins/` 即可。

**真實伺服器測試**：CI 的 *MC Server Test* 工作流會下載 Paper、啟動伺服器、確認外掛通過啟動自檢並可執行 `authkey enroll`；本地亦可執行 `ci/mc-server-test.sh`（需可連網下載 Paper）。

## 設定

複製 `config.example.yml` 為 `config.yml` 並填入你的設定。

> 🔐 **秘密一律走環境變數，切勿寫進設定檔或提交到 Git：**
> - `DISCORD_BOT_TOKEN` — Discord Bot Token
> - `IP_HMAC_SECRET` — 日誌 IP 雜湊用的密鑰鹽
>
> 真實的 `config.yml` 已列入 `.gitignore`。完整鍵說明見 `config.example.yml` 註解。

## 安全性

- 私鑰永不離開客戶端，伺服器只存公鑰（公鑰外洩也無法偽造簽名）。
- 首次註冊需主控台產生的一次性註冊碼，防止搶先註冊（TOFU）。
- 簽名前固定加領域前綴 `MC-ZEROTRUST-AUTH-v1:`，避免成為其他系統的簽名預言機。
- **強烈建議管理員 Discord 帳號開啟 2FA**（選項 B 安全性等同該帳號）。
- 回報漏洞請走私密管道，見 [SECURITY.md](SECURITY.md)；完整威脅模型見 [`docs/ZeroTrust_2FA_Plan.md`](docs/ZeroTrust_2FA_Plan.md)。

## 開發藍圖

| 階段 | 內容 | 狀態 |
|--|--|--|
| Phase 1 | Core 模組（挑戰、簽名驗證、Session、PublicKeyStore、Enrollment）| ✅ 完成（110 測試）|
| Phase 2 | Paper 插件 MVP（凍結、剝奪原版 OP、transient 權限、速率限制）| ✅ 完成（真實伺服器 CI 測試）|
| Phase 3 | Discord Bot 整合，選項 B 帶外驗證 | ✅ 完成（JDA）|
| Phase 4 | Fabric / NeoForge（26.1）＋ Forge（1.20.1 舊版旗艦）伺服器端適配 | ✅ 完成（三載入器 CI 建置）|
| Phase 5 | 客戶端 Mod（選項 A 自動簽名、SSH key 複用，共用 `client-core`）| ✅ 完成（client-fabric/neoforge/forge，CI 建置）|
| Phase 6 | 整合測試、安全審查、文件 | 🔄 進行中（120 單元＋真實 Paper 伺服器＋6 個 mod 建置）|

## 貢獻

歡迎 issue 與 PR。**請勿在 commit／PR 放入任何真實憑證**（Token、Webhook、私鑰、個資）；CI 會執行秘密掃描（gitleaks）與建置。

## 授權

授權方式尚未確定（TBD）。

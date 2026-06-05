# Minecraft 伺服器零信任身份驗證系統
## Zero Trust Authentication — 技術計劃書 v1.4

| 項目 | 內容 |
|------|------|
| 版本 | v1.4 |
| 支援平台 | Paper / Fabric / Forge / NeoForge |
| 驗證方式 | Ed25519 簽名（選項 A）+ Discord 帶外驗證（選項 B）|

---

## 目錄

1. [背景與目標](#1-背景與目標)
2. [系統架構](#2-系統架構)
3. [驗證流程](#3-驗證流程)
4. [Session 管理](#4-session-管理)
5. [各平台實作細節](#5-各平台實作細節)
6. [安全考量](#6-安全考量)
7. [建置計劃](#7-建置計劃)
8. [未來擴充方向](#8-未來擴充方向)

---

## 1. 背景與目標

### 1.1 問題定義

傳統 Minecraft 伺服器以白名單作為主要防線，但白名單僅驗證帳號身份，無法確認登入者是否為帳號的實際擁有者。即使是正版伺服器搭配 Mojang 驗證，一旦帳號遭到盜用，入侵者仍可取得與管理員相同的操作權限。

本系統特別針對以下威脅情境設計：

- 管理員帳號被第三方取得密碼並登入
- 正版帳號 Cookie / Token 被竊取，繞過密碼驗證
- 管理員在陌生環境（公共電腦、借用裝置）登入
- 社交工程攻擊導致帳號暫時落入他人之手

### 1.2 設計原則

本系統以零信任（Zero Trust）為核心哲學：

> **「永遠不信任，持續驗證」— Never Trust, Always Verify**

- 帳號正確不等於身份正確
- 每次登入都重新驗證，不繼承上一次的信任狀態
- 管理員權限不持久化——登出即撤銷，Session 有時效
- 驗證失敗立即通報，不靜默失敗
- **Fail-Closed（預設拒絕）**：當系統自身出現異常（設定損毀、依賴缺失、元件崩潰）時，預設行為一律是「拒絕授予管理員權限」，絕不 fail-open 放行。寧可鎖住自己，不可開門揖盜

### 1.3 一般玩家影響

本系統對一般玩家完全透明，零感知：

| 身份 | 登入體驗 |
|------|---------|
| 一般玩家 | 通過白名單後直接進入遊戲，無任何額外步驟 |
| 管理員帳號 | 登入後凍結，完成驗證才解鎖管理員權限 |

---

## 2. 系統架構

### 2.1 整體架構概覽

系統採用三層式設計，確保核心邏輯與平台無關，便於跨平台部署：

| 層級 | 模組 | 職責 |
|------|------|------|
| 核心層（Core） | `auth/`、`notify/` | TOTP、簽名驗證、Session 管理、Discord 通知 |
| 平台適配層 | `platform-*/` | 事件監聽、玩家凍結、權限授予（各平台各自實作） |
| 客戶端層 | `client-mod/` | 私鑰管理、自動簽名回應（選項 A 專用） |

### 2.2 專案結構

```
mc-zerotrust/
├── core/
│   ├── auth/
│   │   ├── ChallengeManager.java      # Nonce 產生與管理
│   │   ├── SignatureVerifier.java      # Ed25519 公鑰驗證
│   │   ├── AdminSession.java          # Session 狀態機
│   │   └── PublicKeyStore.java        # 公鑰存取（AES 加密）
│   └── notify/
│       ├── DiscordNotifier.java       # Discord Bot 整合
│       └── OutOfBandChallenge.java    # 帶外驗證流程控制
│
├── client-mod/                        # 選項 A 客戶端 Mod
│   ├── KeyManager.java                # Ed25519 金鑰對管理
│   ├── SignatureResponder.java        # 自動簽名回應
│   └── KeyUploadCommand.java          # /authkey upload 指令
│
├── platform-paper/
├── platform-fabric/
├── platform-forge/
└── platform-neoforge/
```

### 2.3 平台適配介面

所有平台實作同一個 `PlatformAdapter` 介面，確保核心邏輯不依賴任何特定平台 API：

```java
public interface PlatformAdapter {
    void freezePlayer(UUID uuid);       // 禁止移動、指令、互動
    void unfreezePlayer(UUID uuid);
    void grantAdminPerm(UUID uuid);     // 動態授予管理員權限
    void revokeAdminPerm(UUID uuid);    // 撤銷管理員權限
    void kickPlayer(UUID uuid, String reason);
    void sendMessage(UUID uuid, String msg);
    void notifyConsole(String msg);     // Discord Webhook / 控制台警報
    boolean isAdminAccount(UUID uuid);  // 從設定檔判斷是否為管理員帳號
}
```

---

## 3. 驗證流程

### 3.1 主流程

```
管理員帳號連線至伺服器
        │
        ▼
Mojang 正版驗證（既有機制）
        │
        ▼
伺服器偵測到管理員帳號
→ 立即凍結角色（禁止一切操作）
        │
        ▼
查詢此帳號是否已登記 Ed25519 公鑰？
        │
   ┌────┴────┐
  有公鑰    無公鑰
   │          │
   ▼          ▼
選項 A      選項 B
發送 Nonce  Discord DM
等待簽名    等待確認
（10秒逾時）
   │          │
   └────┬─────┘
        │
        ▼
   驗證通過？
   ┌────┴────┐
  通過      失敗
   │          │
   ▼          ▼
動態授予    累計失敗次數
管理員權限  ≥ 3 次 → 踢出
記錄日誌    + Discord 警報
        │
        ▼
Session 計時開始（預設 4 小時）
        │
        ▼
登出或 Session 到期
→ 權限自動撤銷
→ 下次登入重新驗證
```

### 3.2 選項 A：Ed25519 簽名驗證

選項 A 最接近 Passkey 的概念——私鑰永不離開管理員的電腦，伺服器只存公鑰。

| 階段 | 動作 | 說明 |
|------|------|------|
| 初次設定 | 客戶端 Mod 產生 Ed25519 金鑰對 | 私鑰以 AES-256 加密後存於本機 |
| 初次設定 | 主控台執行 `/authkey enroll`，遊戲內 `/authkey upload <公鑰> <註冊碼>` | 帶註冊碼上傳，防止搶先註冊（見 3.5）|
| 每次登入 | 伺服器產生 32 bytes 隨機 Nonce | Nonce 30 秒後自動失效，防重放攻擊 |
| 每次登入 | Mod 加領域前綴後用私鑰簽名 Nonce，回傳簽名 | 簽名透過自訂封包傳輸（領域分隔見下）|
| 每次登入 | 伺服器用公鑰驗證簽名 | 通過 → 解凍並授權；失敗 → 計入失敗次數 |

**安全特性：**

- 私鑰永不傳輸至伺服器，公鑰洩漏無法偽造簽名
- 每次 Nonce 不同，無法重放舊簽名
- 已使用的 Nonce 立即作廢（防止 30 秒內重放）
- **Nonce 綁定當前連線**：Nonce 不只綁 UUID，還綁定該次登入的連線 / Session ID，避免簽名被挪用至同帳號的其他連線
- 體驗：管理員登入時完全自動，無需任何手動輸入

> **演算法敏捷性（Crypto Agility）**：領域前綴帶有版本號（`-v1:`），未來若需升級簽名演算法（如後量子方案），可發佈 `-v2:` 前綴並於過渡期同時接受兩版,確保平滑遷移。

> 🔴 **領域分隔（Domain Separation）— 必要措施**：客戶端**絕不可**對伺服器送來的裸 Nonce 直接簽名。否則惡意伺服器可送出「實為其他系統挑戰（SSH 認證、Git commit 等）的雜湊」，把客戶端變成偽造其他 Ed25519 簽名的預言機（Signing Oracle）。**尤其在 SSH key 複用模式下風險極高。**
>
> 修正方式：簽名前固定加上領域前綴，簽 `"MC-ZEROTRUST-AUTH-v1:" + nonce`（連接後再以 SHA-512 雜湊）而非裸 nonce。如此簽出的結果在 SSH 或任何其他場景皆無效，伺服器驗證時也套用同一前綴。

### 3.3 選項 B：Discord 帶外驗證

選項 B 不需要客戶端 Mod，手機 Discord 即為驗證裝置。當選項 A 不可用時（無公鑰或逾時）自動啟用。

```
伺服器產生一次性 Token（32 bytes hex，5 分鐘有效）
        │
        ▼
Discord Bot 發送 DM 至管理員
內容：玩家名稱、時間、確認 / 拒絕按鈕
        │
        ▼
管理員在手機點擊「✅ 確認是我」
        │
        ▼
Discord Bot 通知伺服器後端
Token 驗證完成即作廢（防重放）
        │
        ▼
伺服器解凍玩家，授予管理員權限
```

**DM 訊息格式範例：**

```
⚠️  管理員登入請求

玩家：YourUsername
時間：2026-06-05 14:32:11 UTC

[ ✅ 確認是我 ]    [ ❌ 不是我 ]

此請求將於 5 分鐘後過期
```

> 點擊「❌ 不是我」會立即觸發緊急警報，並建議管理員立即變更帳號密碼。

> ⚠️ **Discord 依賴的殘餘風險**：
> - **Discord 帳號若被盜，選項 B 整個淪陷**（攻擊者能自行批准登入）。因此**強烈建議管理員的 Discord 帳號強制開啟 2FA**，並理解選項 B 的安全性等同於其 Discord 帳號的安全性。
> - **DM 發送失敗**（管理員關閉私訊、Bot 無權 DM）會使選項 B 靜默失效。系統須偵測 DM 失敗，退回至預先設定的私人頻道，並在主控台與日誌回報錯誤。

### 3.4 自動降級邏輯

> ⚠️ **安全注意**：自動降級創造了一個潛在攻擊面——攻擊者若能干擾選項 A 的封包回應，可強迫流程走至選項 B。建議高安全需求環境將 `allow_fallback` 設為 `false`，只允許選項 A 驗證。

```java
public void startAuth(UUID uuid, String playerName) {
    String nonce = challengeManager.issueChallenge(uuid);
    PublicKey pubKey = keyStore.getPublicKey(uuid);
    boolean allowFallback = config.getBoolean("settings.allow_fallback", true);

    if (pubKey != null) {
        // 選項 A：送 Nonce 封包，等待 Mod 回傳簽名
        sendChallengePacket(uuid, nonce);
        scheduleTimeout(uuid, 10, TimeUnit.SECONDS, () -> {
            if (allowFallback) {
                // 允許降級：切換至選項 B
                fallbackToDiscord(uuid, playerName);
            } else {
                // 不允許降級：逾時直接踢出
                adapter.kickPlayer(uuid, "§c驗證逾時，請重新連線");
                adapter.notifyConsole("管理員 " + playerName + " 驗證逾時（已停用降級）");
            }
        });
    } else {
        if (allowFallback) {
            // 無公鑰，走選項 B
            fallbackToDiscord(uuid, playerName);
        } else {
            // 嚴格模式：無公鑰即拒絕
            adapter.kickPlayer(uuid, "§c未登記驗證金鑰，請先設定客戶端 Mod");
        }
    }
}
```

### 3.5 金鑰註冊與信任建立（Enrollment）

> 🔴 **整套系統的安全基礎**：所有安全性都建立在「首次上傳的公鑰確實屬於本人」這個前提上。若攻擊者在管理員設定金鑰**之前**就盜用帳號，他可以上傳**自己的**公鑰，從此成為合法管理員，真正的管理員反而被鎖在門外（信任建立的 TOFU 問題）。

因此首次註冊必須帶外驗證，流程如下：

```
管理員在伺服器主控台執行：
/authkey enroll <player_uuid>
        │
        ▼
伺服器產生一次性註冊碼（enrollment token，10 分鐘有效）
        │
        ▼
管理員在遊戲內執行：
/authkey upload <公鑰> <註冊碼>
        │
        ▼
註冊碼正確且未過期？
   ┌────┴────┐
  是          否
   │          │
   ▼          ▼
公鑰生效    拒絕並觸發警報
```

**要點：**

| 項目 | 說明 |
|------|------|
| 註冊碼來源 | **僅能從伺服器主控台產生**，攻擊者即使盜用帳號也無法取得 |
| 註冊碼強度 | 至少 **128-bit 高熵**亂數，杜絕 10 分鐘內暴力嘗試 |
| 嘗試限制 | 註冊碼輸入錯誤須**速率限制**，連續錯誤觸發 Discord 警報 |
| 一次性 | 用後即廢，10 分鐘自動過期 |
| 換鑰（已有金鑰）| 走 `/authkey rotate`，用既有私鑰簽名證明身份，**不需** enroll |
| 金鑰遺失重設 | 必須重新 enroll，確保只有掌握主控台的人能重建信任 |

> `rotate` 與 `enroll` 的差別：`rotate` 用「你已持有的金鑰」證明身份（信任已存在）；`enroll` 用「主控台」建立信任（信任尚不存在或需重建）。

**伺服器端公鑰驗證**：收到上傳的公鑰時，伺服器必須驗證其合法性，不可照單全收：

- 確認是合法的 **Ed25519 公鑰**（拒絕 RSA / ECDSA / 其他類型，防止金鑰類型混淆）
- 確認位元組結構正確、長度符合（非惡意構造或亂填）
- 解析失敗或格式不符 → 拒絕並記錄

### 3.6 多金鑰 / 多裝置支援

每個管理員帳號可註冊**多把公鑰**，對應不同裝置（桌機、筆電、備用機），如同真實 Passkey 或 SSH `authorized_keys` 的體驗：

| 特性 | 說明 |
|------|------|
| 多把並存 | 一個帳號下可登記多把公鑰，任一把成功簽名即通過驗證 |
| 命名標籤 | 每把金鑰可附 label（如 `desktop`、`laptop`），便於辨識 |
| 獨立撤銷 | 可單獨撤銷某一把（裝置遺失），不影響其他裝置 |
| 最後使用時間 | 每把記錄 last-used 時間戳，供稽核與偵測異常 |

> 多金鑰讓「換裝置」成為日常操作（在舊裝置用 `rotate` 或在新裝置 enroll 一把新的），不必每次都走緊急流程；也讓 4.6 的信任裝置記憶更自然。

**設定檔結構（多金鑰）：**

```yaml
admins:
  - uuid: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
    keys:
      - label: "desktop"
        public_key: "MCowBQYDK2VdAyEA...（Base64）"
        source: "generated"
        last_used: "2026-06-05T14:32:11Z"
      - label: "laptop"
        public_key: "MCowBQYDK2VdAyEB...（Base64）"
        source: "ssh"
        last_used: "2026-06-01T09:10:00Z"
```

### 3.7 指令參考

| 指令 | 執行位置 | 用途 |
|------|---------|------|
| `/authkey enroll <uuid>` | **僅主控台** | 產生一次性註冊碼，建立 / 重建信任 |
| `/authkey upload <公鑰> <註冊碼> [label]` | 遊戲內 | 上傳新公鑰（首次或重設），需附註冊碼 |
| `/authkey rotate [label]` | 遊戲內 | 用既有金鑰證明身份後換新金鑰，免註冊碼 |
| `/authkey verify` | 遊戲內 | 在線重新觸發驗證（Session 到期後恢復權限，見 4.7）|
| `/authkey list` | 遊戲內 | 列出自己名下所有金鑰、label 與最後使用時間（自我稽核）|
| `/authkey revoke <uuid> [label]` | **僅主控台** | 撤銷指定金鑰（省略 label 則撤銷全部），並終止活躍 Session |

---

## 4. Session 管理

### 4.1 Session 狀態機

```
  登入
   │
   ▼
FROZEN ──── 驗證通過 ────▶ VERIFIED
   │                          │
   │ 失敗 3 次                │ 4 小時後 / 登出
   ▼                          ▼
KICKED                     EXPIRED / REVOKED
```

| 狀態 | 說明 | 可執行操作 |
|------|------|-----------|
| `FROZEN` | 剛登入，等待驗證 | 僅能輸入驗證指令，其餘全部封鎖 |
| `VERIFIED` | 驗證通過，持有管理員權限 | 完整管理員功能 |
| `EXPIRED` | Session 超過 TTL 自動失效 | 降為一般權限；可用 `/authkey verify` 在線重新驗證（見 4.7），無需斷線 |
| `REVOKED` | 登出或被強制撤銷 | 需重新登入並驗證 |

### 4.2 Session 參數

| 參數 | 預設值 | 說明 |
|------|--------|------|
| Session TTL | 4 小時 | 驗證通過後的有效時間 |
| Nonce 有效期 | 30 秒 | 選項 A 的挑戰碼有效時間 |
| Token 有效期 | 5 分鐘 | 選項 B 的 Discord Token 有效時間 |
| 最大失敗次數 | 3 次 | 超過後踢出並觸發警報 |
| 自動降級逾時 | 10 秒 | 選項 A 無回應後切換至選項 B |

### 4.3 安全原則

- Session 僅存於記憶體，伺服器重啟後自動清除，需重新驗證
- 管理員登出時立即撤銷權限，不等待 TTL 到期
- 同一帳號的多重連線視為異常，觸發警報
- **伺服器無法強制驗證客戶端是否已安裝 Mod**；選項 A 的啟用條件是「伺服器端已存有該帳號的公鑰」，而非偵測 Mod 存在與否。未上傳公鑰的帳號一律走選項 B 或被拒絕（依 `allow_fallback` 設定）
- **必須剝奪原版 OP（ops.json）**：原版 `/op` 權限獨立於 LuckPerms / 權限 API，寫在 `ops.json`。若管理員帳號在 `ops.json` 中，登入即直接擁有 OP，完全繞過本系統。因此登入時必須**先移除原版 OP 狀態**，驗證通過後才以受控方式恢復；登出 / 撤銷時再次移除。理想做法是要求管理員完全不使用原版 OP，所有權限改由本系統動態授予

### 4.4 BungeeCord / Velocity 環境的 Session 共享

單純「每個後端各自驗證」會造成管理員每次切換子伺服器都需重新驗證，體驗極差。建議架構如下：

```
                  ┌─────────────────────┐
                  │   Proxy 層           │
                  │  BungeeCord/Velocity │
                  │  共享 Session 表     │◀── 驗證通過後寫入
                  └──────┬──────────────┘
                         │ 切換子伺服器時查詢
              ┌──────────┼──────────┐
              ▼          ▼          ▼
          後端 A      後端 B      後端 C
       查到 Session  查到 Session  查到 Session
       → 跳過驗證   → 跳過驗證   → 跳過驗證
```

**實作方式：**

| 方案 | 說明 | 複雜度 |
|------|------|--------|
| Redis 共享 Session | Proxy 與所有後端共用 Redis，Session 寫入後各後端查詢 | 中 |
| Proxy Plugin 中轉 | BungeeCord / Velocity 插件持有 Session 表，後端透過 Plugin Message Channel 查詢 | 低 |

> Session 共享僅在同一 Proxy 網路內有效；跨 Proxy 網路的後端仍需獨立驗證。

> ⚠️ **轉發模式釐清**：本計劃的「Mojang 正版驗證（`online-mode=true`）」描述適用於**單機伺服器**。在 Velocity / BungeeCord 架構下，正版驗證發生在 **Proxy 層**，後端伺服器通常設為 `online-mode=false` 並改用轉發密鑰（Velocity Modern Forwarding / BungeeGuard）信任 Proxy 傳來的身份。此時後端**絕不可直接對外開放**，必須以防火牆限制只接受 Proxy 連線，否則 `offline-mode` 後端會成為任意帳號的入口，反而瓦解整套驗證。

> 🟡 **內網鏈路加密**：玩家連線在 Minecraft 協定層是加密的，但 **Proxy → 後端**這段（offline-mode）預設未必加密。Nonce / 簽名等驗證封包若在內網被竊聽仍有風險，因此此鏈路必須完全隔離（私有網路 / VLAN）或啟用傳輸加密，不可暴露於共用網段。

### 4.5 凍結狀態的完整定義

「凍結」不只是擋住移動和指令，必須涵蓋所有操作向量，否則凍結期間仍有可乘之隙：

| 向量 | 處理 |
|------|------|
| 移動 | 取消位移，僅允許視角轉動 |
| 受傷 / 死亡 | 設為無敵（invulnerable），防止凍結期間被怪物或玩家殺死、防止掉落物 |
| 背包操作 | 鎖定，禁止移動、丟棄或使用物品 |
| 開啟容器 | 禁止（箱子、熔爐、終界箱等）|
| 方塊互動 | 禁止破壞、放置、右鍵互動 |
| 聊天 | 禁止（防止凍結期間洩漏資訊或被當作社交工程管道）|
| 指令 | 僅放行 `/authkey`，其餘全部攔截 |

> 凍結是「驗證前的隔離艙」——玩家在驗證通過前對伺服器世界與其他玩家應為零影響。

> ⚠️ **凍結須在最早的 hook 套用**：若等到玩家完全生成後（如 `PlayerJoinEvent`）才凍結，中間會有約 1 tick 的空窗，玩家可能搶在凍結前完成一個動作（TOCTOU）。凍結標記應在更早階段建立（如 `PlayerLoginEvent` / async pre-login 先標記為待驗證），確保玩家一進場即處於隔離狀態。

### 4.6 信任裝置記憶

為降低斷線重連等情境的驗證摩擦，選項 A 已驗證的裝置可在**登出後 15 分鐘內**重連時免除完整驗證流程：

- 以「公鑰指紋 + 帳號」識別裝置，記憶僅存於記憶體，伺服器重啟即失效
- **選項 A 裝置**：重連時客戶端自動以私鑰簽名一個新 Nonce 即時確認（無需任何手動操作、不經 Discord），因此仍需持有金鑰，帳號被盜也無法冒用
- 上限嚴格設為 **15 分鐘**；超過則視為全新登入，重新走完整驗證
- 此機制只縮短重連摩擦，**不延長 Session TTL 本身**（TTL 仍為 4 小時，到期照常重新驗證）

> ⚠️ 純選項 B（無金鑰）的帳號不適用此記憶——因為缺少金鑰可供重連時即時證明身份，若僅憑帳號放行會在 15 分鐘窗口內形成帳號盜用的破口。選項 B 使用者每次連線仍須 Discord 確認。

設定項：`settings.trusted_device_window_minutes: 15`

### 4.7 到期後的在線重新驗證

Session 到期（EXPIRED）後，管理員降為一般權限。為避免「必須斷線重連才能重新取得權限」的流程斷裂，提供在線重驗機制：

```
管理員在線執行 /authkey verify
        │
        ▼
重新進入 FROZEN（凍結當前操作）
        │
        ▼
走選項 A 簽名 / 選項 B Discord 驗證
        │
        ▼
通過 → 恢復 VERIFIED，重置 TTL 計時
```

- 不需斷線重連，原地完成重新驗證
- 重驗期間同樣套用完整凍結（4.5），驗證前不得操作
- 適用於 TTL 到期、或管理員主動想刷新驗證時效的情境

---

## 5. 各平台實作細節

### 5.1 平台比較

| 平台 | API 類型 | 事件系統 | 權限機制 | 凍結方式 |
|------|---------|---------|---------|---------|
| Paper / Spigot | Plugin API | Bukkit Events | LuckPerms API | `PlayerMoveEvent` 取消 |
| Fabric | Mod API | ServerPlayerEvents | fabric-permissions-api | `ServerTickEvents` 位置重設 |
| Forge | Mod API | Forge Event Bus | ForgeHooks 自訂 | `PlayerEvent` 攔截 |
| NeoForge | Mod API | NeoForge Event Bus | IPermissionHandler | `PlayerEvent` 攔截 |

### 5.2 Paper / Spigot

```java
// 登入事件：偵測管理員帳號並凍結
@EventHandler
public void onJoin(PlayerJoinEvent e) {
    UUID uuid = e.getPlayer().getUniqueId();
    if (!adapter.isAdminAccount(uuid)) return;

    session.freeze(uuid);
    adapter.freezePlayer(uuid);
    adapter.sendMessage(uuid, "§c請完成身份驗證以取得管理員權限");
    authManager.startAuth(uuid, e.getPlayer().getName());
}

// 凍結期間攔截所有指令，僅放行 /authkey
@EventHandler(priority = EventPriority.LOWEST)
public void onCommand(PlayerCommandPreprocessEvent e) {
    UUID uuid = e.getPlayer().getUniqueId();
    if (session.isFrozen(uuid) && !e.getMessage().startsWith("/authkey")) {
        e.setCancelled(true);
    }
}
```

**實作要點：**
- 移動封鎖：`PlayerMoveEvent` 取消，僅允許視角轉動（Head rotation）
- 權限授予：LuckPerms API 動態加入 `admin` 群組；**必須使用 transient node（`user.transientData().add(...)`）**，否則一般 node 會寫入資料庫並在重啟後留存，違反「權限不持久化」原則。transient node 僅存於記憶體，重啟即失效，符合零信任設計
- 封包通訊：自訂 `PluginMessageChannel` 傳送 Nonce 與接收簽名
- **生命週期清理（`onDisable`）**：插件停用或崩潰時，必須主動撤回所有已授予的權限與凍結狀態。否則若僅本插件停止而 LuckPerms 仍運行，已發出的 transient 權限會殘留至 LuckPerms 自行重載為止，形成空窗（fail-closed 的必要環節）

```java
@Override
public void onDisable() {
    // 撤回所有在線管理員的權限，清空 Session
    for (UUID uuid : session.getAllVerified()) {
        adapter.revokeAdminPerm(uuid);
    }
    session.clearAll();
}
```

### 5.3 Fabric

**實作要點：**
- 登入事件：`ServerEntityWorldChangeEvents` 或注入 `PlayerList.addPlayer`
- 移動封鎖：`ServerTickEvents` 每 tick 強制回傳原始座標
- 指令攔截：`CommandRegistrationCallback` 在最高優先級插入驗證檢查
- 權限授予：`fabric-permissions-api` 或自訂 `PermissionProvider`
- 封包通訊：`ServerPlayNetworking.registerGlobalReceiver`

### 5.4 Forge

**實作要點：**
- 登入事件：`PlayerEvent.PlayerLoggedInEvent`
- 移動封鎖：`PlayerMoveEvent` 攔截並取消
- 凍結狀態：使用 Capability 系統儲存每位玩家的驗證狀態
- 指令攔截：`CommandEvent` 並檢查 Capability 凍結狀態
- 封包通訊：`SimpleChannel` 自訂封包

### 5.5 NeoForge

- 事件系統與 Forge 相近，使用 NeoForge Event Bus
- 權限機制：`IPermissionHandler` 整合
- 其餘實作與 Forge 版本結構相同，主要差異在 API import 路徑

### 5.6 客戶端 Mod（選項 A 專用）

客戶端 Mod 需支援 Fabric / Forge / NeoForge，使用 Architectury 共享核心邏輯：

| 模組 | 功能 |
|------|------|
| `KeyManager` | 金鑰來源管理（見下方），支援 SSH key 複用或自動產生 |
| `SignatureResponder` | 監聽自訂封包，收到 Nonce 後**加上領域前綴 `MC-ZEROTRUST-AUTH-v1:` 再簽名**並回傳，玩家無需任何操作（領域分隔細節見 3.2）|
| `KeyUploadCommand` | 提供 `/authkey upload`、`/authkey rotate` 指令，將公鑰以 Base64 格式上傳至伺服器 |

> 未安裝伺服器 Mod 時，客戶端 Mod 靜默不動作，不影響正常遊戲。

#### 5.6.1 金鑰來源：SSH Key 複用

`KeyManager` 支援兩種金鑰來源，啟動時依設定自動選擇：

```
設定 key_source: "ssh" 或 "generated"
        │
   ┌────┴────┐
  ssh     generated
   │          │
   ▼          ▼
讀取本機    Mod 自動產生
SSH 私鑰    Ed25519 金鑰對
```

**SSH Key 複用流程：**

| 步驟 | 說明 |
|------|------|
| 自動偵測 | 掃描 `~/.ssh/` 尋找 Ed25519 格式金鑰（`id_ed25519`）|
| 多金鑰處理 | 若存在多把金鑰，依設定檔指定路徑或提示用戶選擇 |
| Passphrase 解鎖 | 若私鑰有加密保護，彈出輸入框請用戶輸入 Passphrase |
| 格式轉換 | OpenSSH 格式（`-----BEGIN OPENSSH PRIVATE KEY-----`）需透過 Bouncy Castle 解析 |
| 跨平台路徑 | Windows（`%USERPROFILE%\.ssh\`）與 Unix（`~/.ssh/`）自動適配 |

**注意事項：**

- SSH key 同時用於 SSH 登入與 Minecraft 驗證，洩漏影響範圍較大，建議評估風險後選用
- 僅支援 Ed25519 格式的 SSH key（`id_ed25519`）；RSA、ECDSA 格式不相容
- 若用戶無 SSH key，自動退回 `generated` 模式產生專用金鑰

> **建議**：一般情況優先使用 `generated` 模式，產生 Minecraft 專用金鑰，避免與 SSH 登入共用同一把私鑰。`ssh` 模式僅適合已有完善金鑰保護習慣（Passphrase + 安全備份）的進階用戶。

**客戶端設定範例：**

```yaml
# client-mod/config.yml
key_source: "ssh"          # "ssh" 或 "generated"
ssh_key_path: ""           # 留空則自動偵測 ~/.ssh/id_ed25519
```

---

## 6. 安全考量

### 6.1 威脅模型

| 威脅 | 本系統應對方式 | 殘餘風險 |
|------|--------------|---------|
| 帳號密碼洩漏 | 需額外通過 A 或 B 驗證才能取得管理員權限 | 低 |
| Session Token 竊取 | Token 僅存記憶體，短時效，無法跨 Session 使用 | 低 |
| Nonce 重放攻擊 | Nonce 用後即廢，30 秒強制過期 | 極低 |
| 簽名預言機（惡意伺服器騙簽）| 領域分隔前綴 `MC-ZEROTRUST-AUTH-v1:`，簽名在其他場景無效 | 極低 |
| 搶先註冊金鑰（TOFU）| 首次註冊需主控台產生的一次性 enrollment token | 低 |
| 私鑰檔案被竊取 | 私鑰以 AES-256 加密，需要 Passphrase 才能使用 | 中 |
| SSH Key 被竊取（複用模式）| 同上；但影響範圍擴及 SSH 登入，風險略高於專用金鑰 | 中高 |
| 原版 OP 繞過 | 登入時剝奪 ops.json OP，驗證後才受控恢復 | 低 |
| Discord 帳號被盜（選項 B 淪陷）| 建議管理員 Discord 強制 2FA；選項 A 為主要路徑 | 中 |
| Discord Bot 被入侵 | 選項 B 作為備援；平時優先使用選項 A | 中 |
| 惡意 / 畸形公鑰上傳 | 伺服器端驗證金鑰類型與結構，僅接受合法 Ed25519 | 低 |
| 元件崩潰致權限殘留 | Fail-closed + `onDisable` 主動撤回所有權限 | 低 |
| 伺服器資料庫洩漏 | 只存公鑰，公鑰洩漏無法偽造簽名 | 低 |
| `offline-mode` 後端直連（Proxy 架構）| 防火牆限制後端僅接受 Proxy 連線 | 低 |

### 6.2 資料存儲安全

- **公鑰**：Base64 編碼後存於設定檔，洩漏不影響安全性；並記錄金鑰來源（`ssh` 或 `generated`）供日後審計
- **私鑰**（客戶端）：AES-256-GCM 加密，金鑰從 Passphrase 衍生（PBKDF2）
- **Discord Bot Token**：環境變數注入，不存於程式碼或設定檔
- **驗證日誌**：結構化 JSON 格式，欄位定義如下，不記錄任何秘密資料

```json
{
  "timestamp": "2026-06-05T14:32:11Z",
  "player_uuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "player_name": "YourUsername",
  "auth_method": "SIGNATURE_A",
  "result": "SUCCESS",
  "ip_hmac": "hmac_sha256(ip, secret_salt)",
  "session_id": "xxxxxxxx"
}
```

> `auth_method` 可為 `SIGNATURE_A`（Ed25519）、`OUT_OF_BAND_B`（Discord）。`result` 可為 `SUCCESS`、`FAIL`、`DOWNGRADED_A_TO_B`（已從選項 A 降級至 B）、`FALLBACK_DENIED`（嚴格模式拒絕降級）、`TRUSTED_RESUME`（信任裝置 15 分鐘內免驗證重連）。
>
> 🟡 **IP 不可用裸雜湊**：`sha256(ip)` 形同明文——IPv4 僅約 43 億種組合，秒級即可暴力還原。必須使用 **HMAC-SHA256 搭配伺服器密鑰鹽**（`ip_hmac`），密鑰鹽從環境變數 `IP_HMAC_SECRET` 讀取，不寫入設定檔。
>
> **日誌保留與輪替**：JSON 日誌會持續增長，需定義輪替策略（如每日切檔）與保留期限（如 90 天後自動清除），避免無限膨脹並符合資料最小化原則。

### 6.3 系統健全性與 Fail-Closed

零信任系統最關鍵的是「出狀況時往安全的方向倒」。

**啟動自檢（Startup Self-Test）**：伺服器啟動時依序檢查，任一項失敗即進入安全模式（拒絕所有管理員授權並警報）：

| 檢查項 | 失敗時行為 |
|--------|-----------|
| `PublicKeyStore` 可讀取且未損毀 | Fail-closed，拒絕所有管理員 |
| 必要環境變數存在（`DISCORD_BOT_TOKEN`、`IP_HMAC_SECRET`）| Fail-closed |
| Discord 連線正常 | 標記選項 B 不可用，記錄警告 |
| 領域前綴設定與預期一致 | Fail-closed |
| 權限後端（LuckPerms 等）已載入 | Fail-closed |

**運行期失效處理**：

- 設定檔損毀 / 無法讀取 → 拒絕所有管理員授權，不放行
- 權限後端不可用 → 無法安全授權，維持凍結
- 插件崩潰 / 停用 → `onDisable` 主動撤回所有權限與凍結（見 5.2）

> 核心信條：**任何不確定的情況，一律拒絕授權**。鎖住自己可以靠主控台救援，誤放攻擊者則無法挽回。

### 6.4 凍結期間封包速率限制

凍結期間若攻擊者持續發送大量封包嘗試繞過，需加以防護：

- 凍結期間對所有非驗證封包計數，超過閾值（預設每秒 20 個）立即踢出
- 同一 Session 內連續觸發速率限制視為主動攻擊，觸發 Discord 警報
- 速率限制閾值可在設定檔調整：`settings.freeze_packet_limit_per_second`

### 6.5 金鑰管理

**定期輪換（建議每 6 個月）：**

```
管理員執行 /authkey rotate
→ 客戶端 Mod 產生新金鑰對
→ 新公鑰上傳至伺服器
→ 舊公鑰標記為「待淘汰」，保留 24 小時後刪除
→ 24 小時內新舊公鑰均可驗證（避免輪換期間斷線）
```

**緊急撤銷（裝置遺失 / 金鑰洩漏）：**

```
管理員（或其他有主控台存取權的人）執行：
/authkey revoke <uuid>      # 立即撤銷該帳號所有公鑰
                             # 立即終止該帳號當前活躍 Session（若在線即降權 / 踢出）
                             # 該帳號下次登入須重新 enroll
                             # 同時觸發 Discord 緊急警報
```

> 撤銷必須**同時處理進行中的 Session**：若被撤銷帳號當下已是 VERIFIED 狀態，需立即撤回權限並踢出，否則攻擊者的現有 Session 在撤銷後仍能繼續操作直到 TTL 到期。
>
> 緊急撤銷僅能從伺服器主控台執行，不開放遊戲內指令，防止攻擊者在盜用帳號後自行撤銷並重新上傳金鑰。

### 6.6 警報機制

| 事件 | 通報方式 | 緊急程度 |
|------|---------|---------|
| 驗證失敗 1–2 次 | Discord DM 靜默紀錄 | 低 |
| 驗證失敗 3 次（觸發踢出）| Discord DM 即時警報 + @mention | 高 |
| 點擊「不是我」按鈕 | Discord DM 緊急警報，建議立即修改密碼 | 緊急 |
| 同一帳號多重連線 | Discord DM 警報 | 高 |
| 非預期時間登入 | Discord DM 通知（可設定安靜時段）| 中 |
| 凍結期間封包速率超限 | Discord DM 警報（疑似主動攻擊）| 高 |
| 選項 A 降級至選項 B | Discord DM 通知（記錄 `DOWNGRADED_A_TO_B`）| 中 |
| 緊急金鑰撤銷 | Discord DM 緊急警報 | 緊急 |
| 降級被拒（`allow_fallback: false`）| Discord DM 通知 | 中 |
| 金鑰註冊 / 換鑰（enroll / rotate）| Discord DM 通知 | 中 |

### 6.7 Discord 通知速率限制

攻擊者反覆嘗試登入會引發兩個問題：管理員被大量 DM 轟炸（通知 DoS），以及因通知疲勞而養成「習慣性點擊確認」的危險反射。因此：

- 同一帳號的登入請求 DM 設**冷卻時間**（預設 60 秒內最多 1 次確認請求）
- 冷卻期間的重複嘗試**合併計數**，不逐一發送 DM，僅在超過失敗上限時改發單一警報
- 確認按鈕需**明確點擊**，不提供快速 / 預設選項，降低誤觸
- 緊急類警報（撤銷、「不是我」）不受冷卻限制，一律即時送出

設定項：`discord.notify_cooldown_seconds: 60`

---

## 7. 建置計劃

### 7.1 開發里程碑

| 階段 | 內容 | 產出 |
|------|------|------|
| Phase 1 | Core 模組：`ChallengeManager`（含領域分隔）、`SignatureVerifier`、`AdminSession`、`PublicKeyStore`、Enrollment 機制 | 可單元測試的核心邏輯 |
| Phase 2 | Paper 插件完整實作（登入、完整凍結、剝奪原版 OP、transient 權限、封包、速率限制）| 可在 Paper 伺服器運行的 MVP |
| Phase 3 | Discord Bot 整合，選項 B 帶外驗證完整流程 | 雙驗證方案並存 |
| Phase 4 | Fabric / Forge / NeoForge 伺服器端 Mod 平台適配 | 全平台伺服器端支援 |
| Phase 5 | 客戶端 Mod（Fabric 優先，Architectury 跨平台），選項 A 自動簽名、SSH key 複用 | Passkey 體驗完整實現 |
| Phase 6 | 整合測試、安全審查、日誌格式驗證、文件撰寫 | Production-ready 版本 |

### 7.2 技術依賴

| 依賴 | 用途 | 版本要求 |
|------|------|---------|
| Java | 核心開發語言 | 17+（NeoForge 需要 21+）|
| Gradle | 多模組建置系統 | 8.x |
| Architectury | 跨平台 Mod 開發框架 | 最新穩定版 |
| fabric-permissions-api | Fabric 權限管理 | 0.3+ |
| LuckPerms API | Paper 動態權限管理 | 5.4+ |
| JDA（Java Discord API） | Discord Bot 整合 | 5.x |
| Guava Cache | Nonce / Token 快取（自動過期）| 隨 Minecraft 附帶 |
| Bouncy Castle | OpenSSH 私鑰格式解析（SSH key 複用模式）| 1.78+ |

### 7.3 設定檔

```yaml
admins:
  - uuid: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
    keys:                                 # 多金鑰：每個管理員可註冊多把（見 3.6）
      - label: "desktop"
        public_key: "MCowBQYDK2VdAyEA...（Base64）"
        source: "generated"              # "generated" 或 "ssh"，供審計用
        last_used: "2026-06-05T14:32:11Z"
      - label: "laptop"
        public_key: "MCowBQYDK2VdAyEB...（Base64）"
        source: "ssh"
        last_used: "2026-06-01T09:10:00Z"

settings:
  session_ttl_hours: 4
  max_attempts: 3
  option_a_timeout_seconds: 10
  option_b_token_ttl_minutes: 5
  enrollment_token_ttl_minutes: 10     # 首次註冊碼有效期
  enrollment_max_attempts: 5           # 註冊碼錯誤上限，超過觸發警報
  allow_fallback: true                  # false = 嚴格模式，僅允許選項 A
  freeze_packet_limit_per_second: 20    # 凍結期間封包速率限制
  trusted_device_window_minutes: 15     # 信任裝置免驗證重連上限
  strip_vanilla_op: true                # 登入時剝奪 ops.json OP
  fail_closed: true                     # 系統異常時拒絕所有授權（不可關閉）

discord:
  # Bot Token 從環境變數 DISCORD_BOT_TOKEN 讀取
  admin_discord_id: "你的 Discord 用戶 ID"
  notify_webhook: "https://discord.com/api/webhooks/..."
  notify_cooldown_seconds: 60           # 登入請求 DM 冷卻，防通知轟炸
  fallback_channel_id: ""               # DM 失敗時的後備私人頻道
  # 提醒：管理員 Discord 帳號務必自行開啟 2FA，選項 B 安全性等同於此帳號

security:
  # 領域分隔前綴，客戶端與伺服器須一致
  signature_domain: "MC-ZEROTRUST-AUTH-v1:"
  # IP HMAC 密鑰鹽從環境變數 IP_HMAC_SECRET 讀取
  startup_self_test: true               # 啟動自檢，任一項失敗進入安全模式

logging:
  format: "json"              # 結構化日誌
  path: "logs/zerotrust.log"
  rotation: "daily"           # 每日切檔
  retention_days: 90          # 保留 90 天後自動清除

messages:
  prompt_option_a: "§b正在驗證身份，請稍候..."
  prompt_option_b: "§e請在 Discord 確認登入請求"
  success: "§a管理員身份驗證成功"
  fail: "§c驗證失敗，剩餘 {remaining} 次機會"
  kicked: "§c驗證失敗次數過多"
  fallback_disabled: "§c此帳號僅允許金鑰驗證，請確認客戶端 Mod 已安裝"
```

---

## 8. 未來擴充方向

### 8.1 短期（Phase 6 後）

- TOTP 作為第三個驗證選項（選項 A/B 皆不可用時的最後手段）
- Web 後台：查看管理員登入紀錄、管理公鑰、撤銷 Session
- Velocity / BungeeCord 代理層支援，集中管理多後端驗證

### 8.2 中期

- 硬體 Token 支援（YubiKey OTP），作為選項 A 的硬體強化版
- WebAuthn 橋接：透過本地小型 HTTP 服務，讓瀏覽器完成 Passkey 簽名後回傳伺服器
- 安靜時段設定：非工作時間的登入自動升級為更嚴格的驗證流程

### 8.3 長期

- 多管理員規模化管理：集中管理多位管理員的金鑰集、Session 與獨立警報通道（單一管理員多金鑰已於 3.6 納入核心）
- 細粒度時效：不同敏感指令（如 `/op`、`/ban`）需要更近期的驗證時間戳
- 異常偵測：基於登入時間、頻率的行為分析，自動標記可疑登入

---

## 總結

> 本系統以最小化的使用者摩擦達到最大化的管理員帳號安全。
>
> 一般玩家體驗不受任何影響，而管理員身份需要通過兩道獨立的驗證關卡：
> **Mojang 正版驗證（帳號層）** + **Ed25519 簽名或 Discord 帶外確認（裝置層）**。
>
> 就算帳號被完全盜用，沒有管理員的裝置或手機，入侵者依然無法取得任何管理員權限。

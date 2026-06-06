# Forge 舊版線多版本建置 — 進度與接手指南

本檔記錄 Forge **舊版線**（legacy）逐版建置的進度、機制與每版的工具鏈/原始碼差異，供後續 session 接手。
現代線（Paper / Fabric / NeoForge @ 26.1.2）與 Forge 旗艦 1.20.1 已完成並 CI 綠（見 `SUPPORT.md`）。

## 目標版本（使用者精選，7 個）
`1.7.10 · 1.8.9 · 1.12.2 · 1.16.5 · 1.18.2 · 1.19.4 · 1.20.1`
（已剔除採用率低/支援薄弱者：1.9、1.10、1.11、1.13、1.14、1.15、1.17。）

## 目前狀態
| MC | 狀態 | 工具鏈 | 備註 |
|--|--|--|--|
| **1.20.1** | ✅ CI 綠 | ForgeGradle 6 · Gradle 8.10.2 · Java 17 | 旗艦頂版；adapter 源碼基準 |
| **1.19.4** | ✅ CI 綠 | ForgeGradle 6 · Gradle 8.10.2 · Java 17 | 與 1.20.1 共源碼，僅 `sendSuccess` 紀元不同 |
| **1.18.2** | 🟡 工具鏈就緒、源碼待 port | ForgeGradle 6 · Gradle 8.10.2 · Java 17 | **本檔重點**；見下方差異清單 |
| 1.16.5 | ⬜ 待做 | FG **5.1** · Gradle **7.x** · **Java 8** | 跨 Gradle/Java 大關（見「核心 toolchain」注意） |
| 1.12.2 | ⬜ 待做 | FG 3/4 或 RFG · Java 8 | **無 Brigadier**（舊 ICommand）、舊註冊/網路 |
| 1.8.9 | ⬜ 待做 | FG 2.x 或 RFG · Java 8 | 更舊 API |
| 1.7.10 | ⬜ 待做 | **GTNHGradle / RetroFuturaGradle** · Java 8 | 古老 FML；用現代 Gradle 建置 |

> 使用者建議：古老版本（**1.7.10、1.12.2**）改用 **[GTNHGradle](https://github.com/GTNewHorizons/GTNHGradle)**（底層 RetroFuturaGradle），以現代 Gradle 建置，避免死掉的 ForgeGradle 1.2/2.x 工具鏈。

## 多版本建置機制（已實作）
- `-PforgeMc=<ver>` 選版：`platform-forge/build.gradle.kts` 與 `client-forge/build.gradle.kts` 內以 `when(forgeMc)`
  映射 **Forge 製品**（`forgeArtifact`）與 **feedback 紀元**（`feedbackEra`），預設 `1.20.1`。
- **per-era 原始碼 source set**：版本相異的類別放在 `src/feedback-<era>/java`，由
  `sourceSets.named("main"){ java.srcDir("src/feedback-$feedbackEra/java") }` 掛入。目前只有 `CommandFeedback` 墊片：
  - `supplier`（1.20+）：`src.sendSuccess(() -> msg, false)`
  - `component`（≤1.19）：`src.sendSuccess(msg, false)`
- CI：`.github/workflows/mods-forge.yml` 以 `matrix.mc` 對每版各跑一次（pinned Gradle 8.10.2 的 `gradle`，非 wrapper），
  各自上傳 `zerotrustauth-forge-<mc>`。**加版本只需把該 ver 加回 matrix.mc**（build 設定已就緒者）。

## 1.18.2 — 待完成的 per-era adapter（CI 已驗證的差異清單）
工具鏈**沒問題**：FG6 + Gradle 8.10.2 成功解析並 setup `net.minecraftforge:forge:1.18.2-40.2.0`，
跑完 MCP/AT，進到 `compileJava` 才失敗（純源碼 API 差異，非工具鏈）。`build.gradle.kts` 已備好 1.18.2 設定
（`forgeArtifact`、`feedbackEra=component`），只是暫從 `mods-forge.yml` 的 matrix 移除以保持 CI 綠。

需處理的 1.18.2 API 差異（javac 錯誤，共 17 個）：
1. **`Component.literal(String)` 不存在**（1.19 才加入靜態工廠）→ 1.18.2 用 `new TextComponent(String)`。
   到處都有（AuthKeyCommand、ForgePlatformAdapter、ZeroTrustClientForge 的訊息）。
   建議：加一個 per-era `Texts.literal(String): MutableComponent` 墊片（1.19+ 回 `Component.literal`；≤1.18 回 `new TextComponent`），
   把所有 `Component.literal(...)` 改呼叫 `Texts.literal(...)`。
2. **`net.minecraftforge.event.level.BlockEvent` 套件不存在** → 1.18.2 是 `net.minecraftforge.event.world.BlockEvent`
   （`event.world` → `event.level` 是 1.19 改名）。影響 `FreezeHandler`（含 `BlockEvent.BreakEvent`、`BlockEvent.EntityPlaceEvent`）。
3. **`BlockEvent.EntityPlaceEvent#getEntity()` 在 1.18.2 回 `Entity`（非 `Player`）** → 需 `instanceof`/cast。
4. **`CommandSourceStack.getPlayer()` 在 1.18.2 不存在** → 用 `getPlayerOrException()` 或 `getEntity() instanceof ServerPlayer`。

因 (2)(3)(4) 牽涉 import 與型別，`FreezeHandler` 需做**整類 per-era 版本**（放 era source dir）。
建議結構：把版本相異類別（`CommandFeedback`、`Texts`、`FreezeHandler`）統一放 `src/forge-<era>/java`，
共用碼留 `src/main/java`，以 `forgeMc → era` 映射選 source dir（取代目前只分 feedback 的做法）。

## 加入一個新版本的步驟（recipe）
1. `platform-forge/build.gradle.kts` + `client-forge/build.gradle.kts`：`forgeArtifact` 加該版 Forge 製品；`feedbackEra` 歸類；
   若需更舊 FG/Java/Gradle，再參數化（FG 版本可改成 `providers.gradleProperty("fgVersion")` 餵 pluginManagement）。
2. 補齊該版的 per-era 原始碼（墊片或整類），讓 `:platform-forge:build :client-forge:build -PforgeMc=<ver>` 能編譯。
3. `mods-forge.yml`：把 `<ver>` 加回 `matrix.mc`；若該版需不同 pinned Gradle/JDK，於 matrix 帶 per-entry 參數
   （`gradle-version`、`java`、`-PfgVersion`）。
4. **核心 toolchain 注意**：`core`/`platform-common`/`client-core` 目前 toolchain = **JDK 21**。
   1.16.5↓ 需 **Gradle 7.x**（FG 5.1），而 Gradle 7.6 **不支援 Java 21 toolchain** → 屆時須把這三個模組的 toolchain
   降為 **17**（仍 `release 17`，runner 預裝 JDK 17），否則 Gradle 7 的 forge job 會在 core 編譯失敗。
5. CI 綠後更新 `SUPPORT.md` 的「目前已建置」清單。

## 注意事項
- 沙箱無法本地建置（Gradle 9.4 dist + forge/mojang maven 皆封鎖）→ 一律靠 CI 驗證；GitHub Actions API 有 ~13–16 分鐘延遲，
  以「artifact 是否出現」或「job log 是否可下載」判斷成敗最可靠（status 欄會卡在 in_progress）。
- 公開 repo：勿 commit 任何秘密/真實 ID；提交訊息/程式碼勿寫模型識別碼。

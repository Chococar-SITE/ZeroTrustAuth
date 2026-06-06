// client-forge — 選項 A 的 MinecraftForge **客戶端** Mod，**LEGACY 版本線**（Minecraft 1.19.2 / Forge 43.x）。
//
// 跑在「管理員的遊戲客戶端」上：收到伺服器的 Nonce 挑戰封包後，自動加領域前綴簽名回傳；
// 並提供 /ztclient pubkey 指令把公鑰印到聊天，供玩家於伺服器執行 /authkey upload。
//
// 本專案分工：NeoForge 負責現代 Minecraft（1.20.1+），Forge 僅保留給**舊版**。
// 故本模組目標為 **Minecraft 1.19.2 / Java 17 / MinecraftForge 43.x**，使用 ForgeGradle 6
// （net.minecraftforge.gradle，[6.0,6.2)，由 settings.gradle.kts pluginManagement 指定）。
// Forge / Mojang maven 在本沙箱被封鎖，故僅能於 CI（開放網路）建置；
// 驗收標準為 `:client-forge:build` 產出 client mod jar。
//
// 注意（settings.gradle.kts，由人類維護）：需在對應 ztLoader 分支 `include("client-forge")`（見最終報告）。
plugins {
    `java-library`
    // 版本由 settings.gradle.kts 的 pluginManagement 指定（6.0.+ → 落在 [6.0,6.2)），與 platform-forge 一致。
    id("net.minecraftforge.gradle")
}

// 1.19.2 需 Java 17。root 的 subprojects 區塊把 toolchain 設為 21（供現代模組）；
// 本舊版模組於此**覆寫**為 Java 17，使 ForgeGradle 以 JDK 17 反編譯 / 編譯 Minecraft 1.19.2。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    // 目標位元碼 Java 17（1.19.2 執行於 Java 17）。
    options.release.set(17)
    options.encoding = "UTF-8"
}

minecraft {
    // Mojang official mappings（1.19.2），與 platform-forge 一致。
    mappings("official", "1.19.2")
}

// root 已提供 mavenCentral()；ForgeGradle 會自動加入 Forge（minecraft）製品庫。
// 此處不重複宣告 repositories，依賴 root + ForgeGradle 自動注入，避免依賴注入時序問題。

dependencies {
    // Forge 1.19.2 — 43.5.0（43.x 系列，對應 Minecraft 1.19.2），與 platform-forge 一致。
    minecraft("net.minecraftforge:forge:1.19.2-43.5.0")

    // 平台無關的客戶端金鑰 / 簽名邏輯（ClientKeyStore / SignatureResponder）與其依賴的 core。
    // compile-time 即可編譯；執行期內嵌（JiJ）為後續工作（與 platform-* 同一已記錄 follow-up）。
    implementation(project(":client-core"))
    implementation(project(":core"))
}

// platform-forge — MinecraftForge（伺服器端）平台適配，**LEGACY** 版本線（旗艦頂版）。
//
// 本專案分工：NeoForge 負責現代 Minecraft（1.20.1+），Forge 負責**舊版線**，其**頂版**為 1.20.1
//（即 Forge 舊版線與 NeoForge 現代線在 1.20.1 交會：Forge 涵蓋至 1.20.1，NeoForge 自 1.20.1+ 起）。
// 故本模組目標為 **Minecraft 1.20.1 / Java 17 / MinecraftForge 47.x**，使用 ForgeGradle 6
// （net.minecraftforge.gradle，[6.0,6.2)，由 settings.gradle.kts pluginManagement 指定）。
// Forge / Mojang maven 在本沙箱被封鎖，故僅能於 CI（開放網路）建置；驗收標準為
// `:platform-forge:build` 產出 mod jar。
//
// 注意（settings.gradle.kts，由人類維護）：
//   1) pluginManagement 已加入 Forge maven 與 ForgeGradle plugin（6.0.+，落在 [6.0,6.2)）。
//   2) ztLoader 分支已 `"forge" -> include("platform-forge")`。
//
// 執行期函式庫打包（JDA / SnakeYAML 等，計劃待辦）：
//   本模組目前以 compile-time 依賴 :core 與 :platform-common，確保「編譯 + build 產出 jar」。
//   要在真實伺服器執行尚需把 :platform-common 及其傳遞依賴（JDA、SnakeYAML、okhttp...）
//   以 Forge 的 Jar-in-Jar（jarJar）內嵌或宣告為 mod 依賴；此為文件化後續工作，不影響本里程碑。
plugins {
    `java-library`
    // 版本由 settings.gradle.kts 的 pluginManagement 指定（6.0.+ → 落在 [6.0,6.2)）。
    // 1.20.1 的 Forge MDK 使用 ForgeGradle [6.0,6.2)，與本專案 pluginManagement 一致。
    id("net.minecraftforge.gradle")
}

// 1.20.1 需 Java 17。root 的 subprojects 區塊把 toolchain 設為 21（供現代模組）；
// 本舊版模組於此**覆寫**為 Java 17，使 ForgeGradle 以 JDK 17 反編譯 / 編譯 Minecraft 1.20.1。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    // 目標位元碼 Java 17（1.20.1 執行於 Java 17）。
    options.release.set(17)
    options.encoding = "UTF-8"
}

minecraft {
    // Mojang official mappings（1.20.1）。
    mappings("official", "1.20.1")
}

// root 已提供 mavenCentral()；ForgeGradle 會自動加入 Forge（minecraft）製品庫。
// 此處不重複宣告 repositories，依賴 root + ForgeGradle 自動注入，避免依賴注入時序問題。

dependencies {
    // Forge 1.20.1 — 47.3.0（47.x 系列，對應 Minecraft 1.20.1；為實際發布的 1.20.1 Forge 版，
    // 1.20.1 舊版線之旗艦頂版）。
    minecraft("net.minecraftforge:forge:1.20.1-47.3.0")

    // 跨平台核心與共用基礎建設（DiscordNotifier / FileLogSink / YamlConfigLoader / YamlKeyRepository）。
    // compile-time 即可編譯；執行期內嵌（JiJ）為後續工作（見檔首）。
    implementation(project(":core"))
    implementation(project(":platform-common"))

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

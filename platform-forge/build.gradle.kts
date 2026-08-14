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

// ── Forge 多版本（舊版線，分階段逐版推進）──
// 以 -PforgeMc 選 Minecraft 版本（預設 1.20.1）；mods-forge.yml 以 matrix 對每個版本各跑一次。
// 現代 Java-17 世代（1.18.2 / 1.19.4 / 1.20.1，ForgeGradle 6/5）共用同一份 adapter 原始碼。
// 更舊世代（1.16.5↓）API 與工具鏈不同，於後續階段加入；1.7.10 / 1.12.2 規劃改用
// GTNHGradle / RetroFuturaGradle（以現代 Gradle 建置古老版本）。
val forgeMc = providers.gradleProperty("forgeMc").orNull ?: "1.20.1"
val forgeArtifact = when (forgeMc) {
    "1.20.1" -> "1.20.1-47.3.0"  // 47.x；1.20.1 舊版線旗艦頂版
    "1.19.4" -> "1.19.4-45.4.0"  // 45.x（recommended）；ForgeGradle 6 / Java 17
    "1.18.2" -> "1.18.2-40.2.0"  // 40.x（recommended）；Java 17、component 紀元。先試 FG6/Gradle8，失敗再退 FG5.1/Gradle7
    else -> error("platform-forge: 不支援的 forgeMc=$forgeMc（目前支援：1.20.1、1.19.4、1.18.2）")
}

// ── sendSuccess 跨版本紀元（CommandFeedback 墊片）──
// CommandSourceStack.sendSuccess 簽名隨 MC 版本而異，且裸 Component 多載在 1.20 已移除，
// 無法以單一原始碼同時編譯；故依 forgeMc 選入對應的 CommandFeedback 紀元目錄（兩者 public 簽名相同）。
val feedbackEra = when (forgeMc) {
    "1.20.1" -> "supplier"             // 1.20+：sendSuccess(Supplier<Component>, boolean)
    "1.19.4", "1.18.2" -> "component"  // ≤1.19：sendSuccess(Component, boolean)
    else -> error("platform-forge: forge feedback era 未定義：$forgeMc")
}
// 置於 java { } 區塊之後（java 外掛 / main source set 已存在），把紀元目錄掛入 main。
sourceSets.named("main") { java.srcDir("src/feedback-$feedbackEra/java") }

minecraft {
    // Mojang official mappings（依 forgeMc 版本）。
    mappings("official", forgeMc)
}

// root 已提供 mavenCentral()；ForgeGradle 會自動加入 Forge（minecraft）製品庫。
// 此處不重複宣告 repositories，依賴 root + ForgeGradle 自動注入，避免依賴注入時序問題。

dependencies {
    minecraft("net.minecraftforge:forge:$forgeArtifact")

    // 跨平台核心與共用基礎建設（DiscordNotifier / FileLogSink / YamlConfigLoader / YamlKeyRepository）。
    // compile-time 即可編譯；執行期內嵌（JiJ）為後續工作（見檔首）。
    implementation(project(":core"))
    implementation(project(":platform-common"))

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

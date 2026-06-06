// platform-forge — MinecraftForge（伺服器端）平台適配（計劃 Phase 4）。
//
// 目標：Minecraft 1.21.1 / Java 21 / MinecraftForge 52.x，使用 ForgeGradle 6
// （net.minecraftforge.gradle）。Forge / Mojang maven 在本沙箱被封鎖，故僅能於 CI
// （開放網路）建置；驗收標準為 `:platform-forge:build` 產出 mod jar。
//
// 注意（settings.gradle.kts，由人類維護）：
//   1) pluginManagement 需加入 Forge maven（https://maven.minecraftforge.net/）與
//      ForgeGradle plugin 版本，否則 `id("net.minecraftforge.gradle")` 無法解析（見最終報告）。
//   2) 需於 ztLoader 分支加入 `"forge" -> include("platform-forge")`。
//
// 執行期函式庫打包（JDA / SnakeYAML 等，計劃待辦）：
//   本模組目前以 compile-time 依賴 :core 與 :platform-common，確保「編譯 + build 產出 jar」。
//   要在真實伺服器執行尚需把 :platform-common 及其傳遞依賴（JDA、SnakeYAML、okhttp...）
//   以 Forge 的 Jar-in-Jar（jarJar）內嵌或宣告為 mod 依賴；此為文件化後續工作，不影響本里程碑。
plugins {
    `java-library`
    // 版本由 settings.gradle.kts 的 pluginManagement 指定（見檔首說明與最終報告）。
    // ForgeGradle 6（[6.0,6.2)）相容 Gradle 8.x。
    id("net.minecraftforge.gradle")
}

// ForgeGradle 會套用 Java toolchain；此處明確鎖定 Java 21 位元碼（1.21.1 端使用者執行於 Java 21）。
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

minecraft {
    // Mojang official mappings（1.21.1）。與 NeoForge / 1.21.1 端的官方映射一致。
    mappings("official", "1.21.1")
}

// root 已提供 mavenCentral()；ForgeGradle 會自動加入 Forge（minecraft）製品庫。
// 此處不重複宣告 repositories，依賴 root + ForgeGradle 自動注入，避免依賴注入時序問題。

dependencies {
    // Forge 1.21.1 — 52.0.40（52.x 系列，對應 Minecraft 1.21.1）。
    minecraft("net.minecraftforge:forge:1.21.1-52.0.40")

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

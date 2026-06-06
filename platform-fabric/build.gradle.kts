// platform-fabric — Fabric（伺服器端）平台適配（計劃 Phase 4 / 5.3）。
// 依賴 Fabric Loom 與 fabric/mojang maven（非 Maven Central），故僅能在 CI（開放網路）建置。
// 沙箱網路封鎖 fabric/mojang maven → 本機無法解析依賴，請勿在沙箱執行 gradle。
//
// 目標：Minecraft 1.21.1、Java 21、Fabric Loom。
// 成功標準：`:platform-fabric:build` 產出 remap 後的 mod jar。

plugins {
    java
    // Loom 版本由 settings.gradle.kts pluginManagement 指定；此處只套用，不帶版本。
    // Fabric 26.1：no-remap 的 net.fabricmc.fabric-loom（官方對應原生，毋須 remap）。
    id("net.fabricmc.fabric-loom")
}

// Fabric / Minecraft 26.1 需要 Java 25。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

// Fabric 製品庫（fabric-loader / fabric-api / Yarn 等）。Loom 通常會自動注入此庫與 Mojang 庫，
// 此處明示以避免依賴注入時序，並與 pluginManagement 的 Fabric 庫一致。root 已提供 mavenCentral()。
repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

// Fabric 相關版本（對應 Minecraft 26.1）。MC 用 "26.1"（與 fabric-api 的 +26.1 後綴一致；
// Paper / NeoForge 用 26.1.2）。loom 1.15 no-remap：官方對應原生，故不宣告 mappings，
// 且依賴用 implementation（非 modImplementation）——對齊 Fabric 官方 26.1 範本。
val fabricMinecraftVersion = "26.1"
val fabricLoaderVersion = "0.19.2"
val fabricApiVersion = "0.145.1+26.1"

dependencies {
    // Minecraft 本體（Loom no-remap 以官方對應提供；毋須 mappings 宣告）。
    minecraft("com.mojang:minecraft:$fabricMinecraftVersion")

    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // 核心邏輯與跨平台共用基礎建設。
    // 注意：此處為「編譯期」依賴。執行期 jar-in-jar（include(...)）打包 :core / :platform-common
    // 以及其遞移依賴（JDA、SnakeYAML）為後續工作（runtime JiJ 是已記錄的 follow-up，非本階段必要）。
    // TODO(Phase 4 follow-up): 以 `include(project(":core"))`、`include(project(":platform-common"))`
    //   及 `include("net.dv8tion:JDA:...")`、`include("org.yaml:snakeyaml:...")` 將相依函式庫
    //   巢狀打包進 mod jar，使其於真實伺服器執行（含 relocate/shadow 以避免衝突）。
    implementation(project(":core"))
    implementation(project(":platform-common"))
}

// 將專案版本注入 fabric.mod.json（佔位符 ${version}）。
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

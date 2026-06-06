// platform-fabric — Fabric（伺服器端）平台適配（計劃 Phase 4 / 5.3）。
// 依賴 Fabric Loom 與 fabric/mojang maven（非 Maven Central），故僅能在 CI（開放網路）建置。
// 沙箱網路封鎖 fabric/mojang maven → 本機無法解析依賴，請勿在沙箱執行 gradle。
//
// 目標：Minecraft 1.21.1、Java 21、Fabric Loom。
// 成功標準：`:platform-fabric:build` 產出 remap 後的 mod jar。

plugins {
    java
    // Loom 版本由 settings.gradle.kts 的 pluginManagement 指定（人工新增）；此處只套用，不帶版本。
    // 若 pluginManagement 未集中管理版本，可改為 id("fabric-loom") version "1.7.4"。
    id("fabric-loom")
}

// Fabric 1.21.1 需要 Java 21。
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

// Fabric 製品庫（fabric-loader / fabric-api / Yarn 等）。Loom 通常會自動注入此庫與 Mojang 庫，
// 此處明示以避免依賴注入時序，並與 pluginManagement 的 Fabric 庫一致。root 已提供 mavenCentral()。
repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

// 版本集中於 gradle.properties。
val minecraftVersion: String by project

// Fabric 相關版本（對應 Minecraft 1.21.1）。
// 與 plan / CLAUDE.md 一致：fabric-loader 0.16.5、fabric-api 0.102.0+1.21.1。
val fabricLoaderVersion = "0.16.5"
val fabricApiVersion = "0.102.0+1.21.1"

dependencies {
    // Minecraft 本體 + Mojang 官方對應（Loom 提供）。
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

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

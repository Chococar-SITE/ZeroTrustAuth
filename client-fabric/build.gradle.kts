// client-fabric — 選項 A 的 Fabric **客戶端** Mod（計劃 Phase 5.6）。
// 跑在「管理員的遊戲客戶端」上：收到伺服器的 Nonce 挑戰封包後，自動加領域前綴簽名回傳；
// 並提供 /ztclient pubkey 指令把公鑰印到聊天，供玩家於伺服器執行 /authkey upload。
//
// 依賴 Fabric Loom 與 fabric/mojang maven（非 Maven Central），故僅能在 CI（開放網路）建置。
// 沙箱網路封鎖 fabric/mojang maven → 本機無法解析依賴，請勿在沙箱執行 gradle。
//
// 目標：Minecraft 1.21.1、Java 21、Fabric Loom（鏡像 platform-fabric，但為 CLIENT 端）。
// 成功標準：`:client-fabric:build` 產出 remap 後的 client mod jar。
plugins {
    java
    // Loom 版本由 settings.gradle.kts 的 pluginManagement 指定（與 platform-fabric 一致）；此處只套用。
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

// Fabric 製品庫（fabric-loader / fabric-api / Yarn 等）。與 platform-fabric 一致明示。
repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

// 版本集中於 gradle.properties。
val minecraftVersion: String by project

// Fabric 相關版本（對應 Minecraft 1.21.1）。與 platform-fabric / CLAUDE.md 一致。
val fabricLoaderVersion = "0.16.5"
val fabricApiVersion = "0.102.0+1.21.1"

dependencies {
    // Minecraft 本體 + Mojang 官方對應（Loom 提供）。
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // 平台無關的客戶端金鑰 / 簽名邏輯（ClientKeyStore / SignatureResponder）與其依賴的 core。
    // 注意：此處為「編譯期」依賴。執行期 jar-in-jar（include(...)）打包 :client-core / :core
    // 及其遞移依賴（BouncyCastle）為後續工作（runtime JiJ，與 platform-fabric 同一已記錄 follow-up）。
    implementation(project(":client-core"))
    implementation(project(":core"))
}

// 將專案版本注入 fabric.mod.json（佔位符 ${version}）。
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

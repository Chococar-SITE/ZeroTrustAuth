pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        // Fabric 26.1：no-remap 的 net.fabricmc.fabric-loom（官方對應原生，毋須 remap）。
        // 版本用 1.16-SNAPSHOT——對齊 Fabric 官方 26.1 範本（fabric-example-mod）；
        // 1.15 未以此新 plugin id 發布（解析失敗），26.1 的 loom 目前為 1.16-SNAPSHOT。
        id("net.fabricmc.fabric-loom") version "1.16-SNAPSHOT"
        // NeoForge 26.1：ModDevGradle 2.0.141。
        id("net.neoforged.moddev") version "2.0.141"
        // Forge 舊版線（1.20.1）：ForgeGradle 6（Round 3 以自有 pinned Gradle 8 建置，見 mods.yml）。
        id("net.minecraftforge.gradle") version "6.0.+"
    }
}

rootProject.name = "ZeroTrustAuth"

// ── 可本地建置的 JVM 模組（純 Maven Central）──
include("core")
include("platform-common")
include("platform-paper")
include("client-core")

// ── 平台載入器模組：依賴各自 maven（沙箱封鎖），僅在明確建置時納入，
//    使一般 build 與本地測試不受其重型工具鏈影響。以 -PztLoader=<name> 或 ZT_LOADER 啟用。──
when (providers.gradleProperty("ztLoader").orNull ?: System.getenv("ZT_LOADER")) {
    "fabric" -> { include("platform-fabric"); include("client-fabric") }
    "neoforge" -> { include("platform-neoforge"); include("client-neoforge") }
    "forge" -> { include("platform-forge"); include("client-forge") }
    "all" -> {
        include("platform-fabric"); include("client-fabric")
        include("platform-neoforge"); include("client-neoforge")
        include("platform-forge"); include("client-forge")
    }
}

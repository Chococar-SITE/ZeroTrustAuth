pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("fabric-loom") version "1.7.4"
        id("net.neoforged.moddev") version "2.0.78"
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
    "fabric" -> include("platform-fabric")
    "neoforge" -> include("platform-neoforge")
    "forge" -> include("platform-forge")
    "all" -> {
        include("platform-fabric")
        include("platform-neoforge")
        include("platform-forge")
    }
}

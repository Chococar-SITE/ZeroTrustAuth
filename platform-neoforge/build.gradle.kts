// platform-neoforge — NeoForge (server-side) 平台適配（計劃 Phase 4）。
//
// 目標：Minecraft 1.21.1 / Java 21 / NeoForge 21.1.x，使用 ModDevGradle
// （net.neoforged.moddev）。NeoForge / Mojang maven 在本沙箱被封鎖，故僅能於 CI
// （開放網路）建置；驗收標準為 `:platform-neoforge:build` 產出 mod jar。
//
// 注意（settings.gradle.kts，由人類維護）：
//   1) pluginManagement 需加入 NeoForged maven 與 moddev plugin 版本，否則
//      `id("net.neoforged.moddev")` 無法解析（見最終報告）。
//   2) 需 `include("platform-neoforge")`。
//
// 執行期函式庫打包（JDA / SnakeYAML 等 Jar-in-Jar，計劃待辦）：
//   本模組目前以 compile-time 依賴 :core 與 :platform-common，確保「編譯 + build 產出 jar」。
//   要在真實伺服器執行尚需把 :platform-common 及其傳遞依賴（JDA、SnakeYAML、okhttp...）
//   以 NeoForge Jar-in-Jar（jarJar）內嵌或宣告為 mod 依賴；此為文件化後續工作，不影響本里程碑。
plugins {
    `java-library`
    // 版本由 settings.gradle.kts 的 pluginManagement 指定（見檔首說明與最終報告）。
    id("net.neoforged.moddev")
}

// NeoForge / Minecraft 26.1 需要 Java 25；明確鎖定 JDK 25 toolchain 與 Java 25 位元碼。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

neoForge {
    // NeoForge 26.1.2.71（對應 Minecraft 26.1.2；ModDevGradle 2.0.141）。
    version = "26.1.2.71"

    // mod 版本中繼資料寫死於 META-INF/neoforge.mods.toml（version="0.1.0"），不依賴佔位符插值，
    // 避免不同 ModDevGradle 版本展開行為不一致而導致建置不穩。

    // 本里程碑僅需 `build` 組出 jar，不需 runClient/runServer；仍宣告 server run 與 mods 綁定，
    // 以滿足 ModDevGradle 對「mod 需綁定 source set」的要求並保留日後執行測試能力。
    runs {
        create("server") {
            server()
        }
    }

    mods {
        create("zerotrustauth") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    // 跨平台核心與共用基礎建設（DiscordNotifier / FileLogSink / YamlConfigLoader / YamlKeyRepository）。
    // compile-time 即可編譯；執行期內嵌（JiJ）為後續工作（見檔首）。
    implementation(project(":core"))
    implementation(project(":platform-common"))

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

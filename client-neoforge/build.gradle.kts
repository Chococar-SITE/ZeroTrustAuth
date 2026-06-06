// client-neoforge — 選項 A 的 NeoForge **客戶端** Mod（計劃 Phase 5.6）。
// 跑在「管理員的遊戲客戶端」上：收到伺服器的 Nonce 挑戰封包後，自動加領域前綴簽名回傳；
// 並提供 /ztclient pubkey 指令把公鑰印到聊天，供玩家於伺服器執行 /authkey upload。
//
// 目標：Minecraft 1.21.1 / Java 21 / NeoForge 21.1.x，使用 ModDevGradle（net.neoforged.moddev）。
// NeoForge / Mojang maven 在本沙箱被封鎖，故僅能於 CI（開放網路）建置；
// 驗收標準為 `:client-neoforge:build` 產出 client mod jar。
//
// 注意（settings.gradle.kts，由人類維護）：需在對應 ztLoader 分支 `include("client-neoforge")`
// （見最終報告）。pluginManagement 的 NeoForged maven 與 moddev plugin 版本沿用既有設定。
plugins {
    `java-library`
    // 版本由 settings.gradle.kts 的 pluginManagement 指定（與 platform-neoforge 一致）。
    id("net.neoforged.moddev")
}

// ModDevGradle 會套用 Java toolchain；明確鎖定 Java 21 位元碼（1.21.1 客戶端執行於 Java 21）。
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

neoForge {
    // NeoForge 21.1.x（對應 Minecraft 1.21.1），與 platform-neoforge 一致。
    version = "21.1.95"

    // mod 版本中繼資料寫死於 META-INF/neoforge.mods.toml（version="0.1.0"），不依賴佔位符插值，
    // 避免不同 ModDevGradle 版本展開行為不一致而導致建置不穩。

    // 本里程碑僅需 `build` 組出 jar；客戶端 mod 需 GUI client 才能真正執行測試（無自動化執行測試）。
    // 仍宣告 client run 與 mods 綁定，以滿足 ModDevGradle 對「mod 需綁定 source set」的要求。
    runs {
        create("client") {
            client()
        }
    }

    mods {
        create("zerotrustauthclient") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    // 平台無關的客戶端金鑰 / 簽名邏輯（ClientKeyStore / SignatureResponder）與其依賴的 core。
    // compile-time 即可編譯；執行期內嵌（JiJ）為後續工作（與 platform-* 同一已記錄 follow-up）。
    implementation(project(":client-core"))
    implementation(project(":core"))
}

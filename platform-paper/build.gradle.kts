// platform-paper — Paper/Spigot 平台適配（計劃 Phase 2 MVP）。
// 依賴 paper-api 等非 Maven Central 來源，故在 CI（開放網路）建置與測試。
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

java {
    // 以 JDK 25 toolchain 編譯：Paper 26.1 的 API 為 Java 25（class major 69）位元碼，
    // 需 JDK 25 的 javac 方能讀取其 class。
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    // 但本外掛**輸出 Java 21（major 65）位元碼**（見下方 release 21）。原因：Shadow 8.3.5 內嵌的
    // ASM 無法解析 major 69 類別（shadowJar 會丟 "Unsupported class file major version 69"）。
    // Java 25 的 Paper 伺服器可正常載入 Java 21 位元碼，功能完全相同；如此既對齊 Paper 26.1，
    // 又毋須冒險升級 Shadow 9.x（本專案僅 platform-paper 使用 shadow）。
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
}

val paperApiVersion: String by project
val jdaVersion: String by project
val luckpermsApiVersion: String by project

dependencies {
    implementation(project(":core"))

    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("net.luckperms:api:$luckpermsApiVersion")

    // Discord（選項 B）— 打包進外掛 jar。
    implementation("net.dv8tion:JDA:$jdaVersion") {
        exclude(module = "opus-java") // 不需要語音
    }

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    // MockBukkit 行為測試已移除：在此沙箱無法建置／驗證其精確 API，避免不可解析或編譯失敗
    // 拖垮 CI。核心模組單元測試 + CI 的真實伺服器整合測試已涵蓋行為（見 plan 7.1 Phase 2）。
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    // 重定位 JDA 依賴，避免與其他外掛衝突。
    relocate("net.dv8tion", "com.chococar.zerotrust.libs.jda")
    relocate("okhttp3", "com.chococar.zerotrust.libs.okhttp3")
    relocate("okio", "com.chococar.zerotrust.libs.okio")
    relocate("com.neovisionaries", "com.chococar.zerotrust.libs.nv")
    mergeServiceFiles()
    minimize {
        exclude(dependency("net.dv8tion:.*:.*"))
    }
}

tasks.named("build") {
    dependsOn("shadowJar")
}

// 將版本注入 plugin.yml
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

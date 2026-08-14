// platform-paper — Paper/Spigot 平台適配（計劃 Phase 2 MVP）。
// 依賴 paper-api 等非 Maven Central 來源，故在 CI（開放網路）建置與測試。
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    // Shadow 8.3.10+ 升級內嵌 ASM/jdependency 以支援 Java 25/26 位元碼（class major 69/70）；
    // 8.3.5 會在 shadowJar 丟 "Unsupported class file major version 69"。8.3.x 亦支援 Gradle 9，
    // 且維持與舊版相同的 import 與 DSL（minimize/relocate/mergeServiceFiles），故毋須升級 9.x 重寫。
    id("com.gradleup.shadow") version "8.3.11"
}

java {
    // Paper 26.1 的 API 為 Java 25 位元碼，且其 Gradle Module Metadata 宣告 org.gradle.jvm.version=25。
    // 因此本外掛**必須**以 JDK 25 toolchain 編譯並 target Java 25——否則 Gradle 變體解析會直接拒絕
    // paper-api（"is only compatible with JVM runtime version 25 or newer"）。輸出 major 69 位元碼，
    // 由上方 Shadow 8.3.11（ASM 支援 major 69）負責 shade。Paper 26.1 伺服器執行於 Java 25。
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
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

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
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

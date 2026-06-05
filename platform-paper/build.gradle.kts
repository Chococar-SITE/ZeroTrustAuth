// platform-paper — Paper/Spigot 平台適配（計劃 Phase 2 MVP）。
// 依賴 paper-api 等非 Maven Central 來源，故在 CI（開放網路）建置與測試。
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

java {
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
    // MockBukkit（行為測試，CI 解析）
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.39.1")
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

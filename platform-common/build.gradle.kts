// platform-common — 跨平台共用、與遊戲載入器無關的基礎建設。
// 僅依賴 :core + JDA + SnakeYAML（皆 Maven Central）→ 可本地建置與測試。
// 供 Fabric / Forge / NeoForge 模組共用（Paper 維持自有實作不動）。
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

val jdaVersion: String by project

dependencies {
    api(project(":core"))

    // Discord（選項 B）：公開 API 不外露 JDA 型別，故 implementation 即可。
    implementation("net.dv8tion:JDA:$jdaVersion") {
        exclude(module = "opus-java")
    }
    // 非 Bukkit 平台的 YAML 設定 / 金鑰庫解析。
    implementation("org.yaml:snakeyaml:2.3")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

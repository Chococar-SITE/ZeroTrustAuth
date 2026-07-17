// core — 平台無關的零信任驗證核心邏輯。
// 僅依賴 JDK（Ed25519 / HMAC / SHA-512 皆為內建）+ 測試函式庫，
// 因此可在任何環境（含受限網路）本地建置與測試。
plugins {
    java
}

java {
    // 目標 Java 17 位元碼（由 options.release=17 控制），最大化平台相容性（計劃要求 Java 17+）。
    // Toolchain 用 JDK 21（本地預設、GitHub runner 預裝、Gradle 8/9 皆支援）編譯：
    // 如此 core 同時可在 Gradle 9（現代線）與 Gradle 8（Forge 舊版線）建置，且毋須另裝 JDK 17/25。
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

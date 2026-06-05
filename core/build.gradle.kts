// core — 平台無關的零信任驗證核心邏輯。
// 僅依賴 JDK（Ed25519 / HMAC / SHA-512 皆為內建）+ 測試函式庫，
// 因此可在任何環境（含受限網路）本地建置與測試。
plugins {
    java
}

java {
    // 目標 Java 17 位元碼，最大化平台相容性（計劃要求 Java 17+）。
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

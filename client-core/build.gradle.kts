// client-core — 選項 A 客戶端的平台無關金鑰與簽名邏輯（計劃 5.6）。
// 純 JDK（Ed25519 內建）+ BouncyCastle（OpenSSH 私鑰解析），皆 Maven Central → 可本地測試。
// 各載入器的客戶端 Mod（client-fabric/forge/neoforge）在此之上做封包與指令接線。
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

dependencies {
    // 共用簽名訊息構造（SHA-512(domain||nonce)）與伺服器一致。
    api(project(":core"))
    // OpenSSH Ed25519 私鑰解析（SSH key 複用模式）。
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

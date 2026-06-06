plugins {
    java
}

subprojects {
    apply(plugin = "java")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        // Toolchain 由各模組自行宣告，root 不再強制單一版本：
        //   * core / platform-common / client-core → JDK 17（與 Gradle 8 的 Forge 舊版線相容的交集）。
        //   * 現代載入器（Paper / Fabric / NeoForge @ 26.1）→ JDK 25。
        // 如此可在同一 repo 內並存「Gradle 9（現代線）」與「Gradle 8（Forge 舊版線）」兩套工具鏈。
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    // 中文註解：確保 Javadoc / 編譯使用 UTF-8 且不因 doclint 失敗。
    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).apply {
            charSet = "UTF-8"
            docEncoding = "UTF-8"
            addStringOption("Xdoclint:none", "-quiet")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

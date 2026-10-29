plugins {
    java
    application
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.javaparser:javaparser-core:3.28.2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Gradle 9 ではテスト実行に launcher を明示的に追加する必要がある
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.example.stubgen.StubGenerator"
}

tasks.withType<Test> {
    useJUnitPlatform()
    if (project.hasProperty("verboseTest")) {
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}

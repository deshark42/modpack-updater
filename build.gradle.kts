plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "io.github.deshark42"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Source: https://mvnrepository.com/artifact/org.slf4j/slf4j-api
    implementation("org.slf4j:slf4j-api:2.0.20")
    // Source: https://mvnrepository.com/artifact/org.tinylog/tinylog-impl
    implementation("org.tinylog:tinylog-impl:2.8.1")
    // Source: https://mvnrepository.com/artifact/org.tinylog/slf4j-tinylog
    runtimeOnly("org.tinylog:slf4j-tinylog:2.8.1")
    // Source: https://mvnrepository.com/artifact/com.google.code.gson/gson
    implementation("com.google.code.gson:gson:2.14.0")
    // Source: https://mvnrepository.com/artifact/org.apache.commons/commons-compress
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Source: https://mvnrepository.com/artifact/org.tukaani/xz
    implementation("org.tukaani:xz:1.12")

    // Source: https://mvnrepository.com/artifact/org.junit/junit-bom
    implementation(platform("org.junit:junit-bom:6.1.3"))
    // Source: https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "io.github.deshark42.modpackupdater.Main")
    }
}
tasks.jar { enabled = false }

plugins {
    kotlin("jvm") version "1.9.10"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "com.rsplwe.esurfing"
version = "1.4.0-beta.2"

repositories {
    maven(url = "https://maven.aliyun.com/repository/public")
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("com.github.zhkl0228:unicorn:1.0.13")
    implementation("com.github.zhkl0228:unidbg-android:0.9.7")
    implementation("com.github.zhkl0228:unidbg-api:0.9.7")
    implementation("com.github.zhkl0228:unidbg-dynarmic:0.9.7")
    implementation("com.github.zhkl0228:unidbg-unicorn2:0.9.7")
    implementation("org.slf4j:slf4j-log4j12:2.0.9")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.github.yescallop:fluent-uri:d8e12f23af")
    implementation("commons-cli:commons-cli:1.5.0")
    implementation("commons-codec:commons-codec:1.16.0")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.rsplwe.esurfing.DialerApp")
}

val authenticationRegression by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run offline Docker authentication boundary regressions without credentials or native loading."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rsplwe.esurfing.AuthenticationRegression")
    environment("STATE_DIR", layout.buildDirectory.dir("authentication-regression-state").get().asFile.absolutePath)
}
tasks.check { dependsOn(authenticationRegression) }

val operationsRegression by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rsplwe.esurfing.OperationsRegression")
    environment("NETWORK_CHECK_INTERVAL_SECONDS", "300")
    environment("STATE_DIR", layout.buildDirectory.dir("operations-regression-state").get().asFile.absolutePath)
}
tasks.check { dependsOn(operationsRegression) }

val supervisionRegression by tasks.registering(Exec::class) {
    group = "verification"
    commandLine("python", "scripts/check-supervision.py", "--report", "build/supervision-regression.json")
}
tasks.check { dependsOn(supervisionRegression) }

val logRegression by tasks.registering(Exec::class) {
    group = "verification"
    commandLine("python", "scripts/check-logs.py")
}
tasks.check { dependsOn(logRegression) }

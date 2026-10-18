import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Protocol engines; Android supplies URI-backed storage and service lifetime.
    implementation("org.apache.sshd:sshd-sftp:2.20.0")
    implementation("org.jupnp:org.jupnp:3.0.5")
    implementation("org.jupnp:org.jupnp.support:3.0.5")
    implementation("org.apache.httpcomponents.core5:httpcore5:5.3.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.17")

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// A real socket server for scripts/check-sharing.py, not a mocked unit test.
tasks.register<JavaExec>("sharingSmokeServer") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.changyow.mediadler.core.sharing.SharingSmokeServerKt")
    standardInput = System.`in`
    args(providers.gradleProperty("shareRoot").orElse("/tmp/media-dler-sharing").get())
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

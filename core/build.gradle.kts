plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    `maven-publish`
}
kotlin {
    androidTarget { publishLibraryVariants("release") }
    jvm()
    jvmToolchain(21)
    sourceSets {
        commonMain.dependencies { api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
android {
    namespace = "io.motionengine.core"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.withType<Test>().configureEach {
    systemProperty("motion.fixtures", rootProject.file("fixtures").absolutePath)
}

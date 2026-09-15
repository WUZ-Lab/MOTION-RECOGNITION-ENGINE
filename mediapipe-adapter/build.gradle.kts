plugins { id("com.android.library"); kotlin("android"); `maven-publish` }
android {
    namespace = "io.motionengine.mediapipe"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core"))
    api("com.google.mediapipe:tasks-vision:0.10.26")
}
afterEvaluate {
    publishing.publications.create<MavenPublication>("release") { from(components["release"]) }
}

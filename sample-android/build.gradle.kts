plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "io.motionengine.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.motionengine.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += listOf("tflite", "task") }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":android-runtime"))
    implementation(project(":mediapipe-adapter"))
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
}

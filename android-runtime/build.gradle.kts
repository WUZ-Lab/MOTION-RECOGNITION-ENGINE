plugins { id("com.android.library"); kotlin("android"); `maven-publish` }
android {
    namespace = "io.motionengine.runtime"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core"))
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
afterEvaluate {
    publishing.publications.create<MavenPublication>("release") { from(components["release"]) }
}

val modelBundle = providers.gradleProperty("modelBundle")
val prepareModelTestAssets by tasks.registering(Sync::class) {
    if (modelBundle.isPresent) {
        from(rootProject.file(modelBundle.get())) {
            include("manifest.json", "motion.tflite", "parity.json")
            into("bundle")
        }
        providers.gradleProperty("replaySession").orNull?.let { session ->
            from(rootProject.file(session)) { into("bundle"); rename { "session.jsonl" } }
        }
    }
    into(layout.buildDirectory.dir("generated/modelTestAssets"))
}
if (modelBundle.isPresent) {
    android.sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/modelTestAssets"))
    android.defaultConfig.testInstrumentationRunnerArguments["requireModelBundle"] = "true"
    tasks.matching {
        it.name.contains("AndroidTest") && (it.name.endsWith("Assets") || it.name.endsWith("LintModel"))
    }.configureEach { dependsOn(prepareModelTestAssets) }
}

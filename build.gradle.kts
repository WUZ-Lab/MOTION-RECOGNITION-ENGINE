import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    kotlin("multiplatform") version "2.2.20" apply false
    kotlin("android") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    id("com.android.library") version "8.13.2" apply false
    id("com.android.application") version "8.13.2" apply false
}

val sdkVersion = providers.gradleProperty("sdkVersion").orElse("0.1.0-SNAPSHOT").get()
require(sdkVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"))) {
    "sdkVersion must use MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-suffix (for example 0.1.0 or 0.1.0-rc.1)"
}
val githubRepository = providers.gradleProperty("githubRepository")
    .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
    .orElse("WUZ-Lab/MOTION-RECOGNITION-ENGINE").get()
require(githubRepository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
    "githubRepository must use OWNER/REPOSITORY"
}
val repositoryOwner = githubRepository.substringBefore('/').lowercase(java.util.Locale.ROOT)
val repositoryName = githubRepository.substringAfter('/')
val githubPackagesUrl = "https://maven.pkg.github.com/$repositoryOwner/$repositoryName"
val githubUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR"))
val githubToken = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN"))

val validateGitHubPackagesCredentials = tasks.register("validateGitHubPackagesCredentials") {
    group = "publishing"
    description = "Checks credentials only when publishing to GitHub Packages."
    doLast {
        check(!githubUser.orNull.isNullOrBlank() && !githubToken.orNull.isNullOrBlank()) {
            "GitHub Packages requires gpr.user/gpr.key in ~/.gradle/gradle.properties or GITHUB_ACTOR/GITHUB_TOKEN."
        }
    }
}

allprojects {
    group = "io.motionengine"
    version = sdkVersion
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

subprojects {
    // Keep the KMP publications created by Kotlin, including Android and JVM variants.
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri(githubPackagesUrl)
                    credentials {
                        username = githubUser.orNull
                        password = githubToken.orNull
                    }
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set("Motion Recognition Engine - ${project.name}")
                    description.set("On-device exercise recognition and repetition counting SDK")
                    url.set("https://github.com/$githubRepository")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    scm {
                        url.set("https://github.com/$githubRepository")
                        connection.set("scm:git:https://github.com/$githubRepository.git")
                        developerConnection.set("scm:git:ssh://git@github.com/$githubRepository.git")
                    }
                }
            }
        }
        tasks.withType<PublishToMavenRepository>().configureEach {
            if (name.endsWith("ToGitHubPackagesRepository")) {
                dependsOn(validateGitHubPackagesCredentials)
            }
        }
    }
}

tasks.register("publishGitHubPackages") {
    group = "publishing"
    description = "Publishes all SDK modules and KMP variants to GitHub Packages."
    dependsOn(
        ":core:publishAllPublicationsToGitHubPackagesRepository",
        ":android-runtime:publishAllPublicationsToGitHubPackagesRepository",
        ":mediapipe-adapter:publishAllPublicationsToGitHubPackagesRepository",
    )
}

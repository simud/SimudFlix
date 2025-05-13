import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.android.build.gradle.BaseExtension

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.6.0")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    // Applica i plugin solo al modulo StreamingCommunity
    if (name == "StreamingCommunity") {
        apply(plugin = "com.android.library")
        apply(plugin = "kotlin-android")
        apply(plugin = "com.lagradost.cloudstream3.gradle")

        cloudstream {
            setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/doGior/doGiorsHadEnough")
        }

        android {
            compileSdkVersion(35) // Usa compileSdkVersion invece di compileSdk

            defaultConfig {
                minSdkVersion(21)
                targetSdkVersion(35)
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_1_8
                targetCompatibility = JavaVersion.VERSION_1_8
            }
        }
    }

    // Configura le opzioni di compilazione Kotlin per tutti i moduli
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                "-Xno-call-assertions",
                "-Xno-param-assertions",
                "-Xno-receiver-assertions",
                "-Xjsr305=strict",
                "-opt-in=kotlin.RequiresOptIn"
            )
        }
    }

    dependencies {
        // Configura le dipendenze solo per StreamingCommunity
        if (name == "StreamingCommunity") {
            val apk by configurations
            val implementation by configurations

            apk("com.lagradost:cloudstream3:pre-release")
            implementation(kotlin("stdlib"))
            implementation("com.github.Blatzar:NiceHttp:0.4.11")
            implementation("org.jsoup:jsoup:1.18.1")
            implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
            implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

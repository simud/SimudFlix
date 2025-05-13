plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "it.dogior.hadEnough"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        testOptions.targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

cloudstream {
    version = 17
    description = "TV Shows and Movies from StreamingCommunity (now StreamingUnity)"
    authors = listOf("doGior")
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Documentary",
        "Cartoon"
    )
    requiresResources = false
    language = "it"
    iconUrl = "https://streamingunity.to/apple-touch-icon.png?v=2"
}

dependencies {
    val apk by configurations
    val implementation by configurations

    apk("com.lagradost:cloudstream3:pre-release")
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
}

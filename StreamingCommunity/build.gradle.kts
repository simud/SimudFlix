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
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

cloudstream {
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/doGior/doGiorsHadEnough")
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

rootProject.name = "SimudFlix"

// Includi i moduli della repository
include(":StreamingCommunity")
include(":scraper")

// Configura i repository per tutti i progetti
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // Necessario per NiceHttp
    }
}

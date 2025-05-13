plugins {
    kotlin("jvm")
    java
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.register<JavaExec>("run") {
    group = "execution"
    description = "Run the Scraper Kotlin script"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ScraperKt")
    // Debug per il classpath e le classi compilate
    doFirst {
        println("Classpath: ${classpath.files.joinToString("\n")}")
        val classesDir = file("build/classes/kotlin/main")
        println("Classi compilate in $classesDir:")
        classesDir.walk().forEach { println(it) }
    }
}

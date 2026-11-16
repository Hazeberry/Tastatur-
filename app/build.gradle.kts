plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.hazeberry.keyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.hazeberry.keyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // In Unit-Tests liefern Android-Aufrufe wie Log.d Standardwerte statt abzustürzen.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // PDF-Text lesen, komplett offline (Apache PDFBox für Android, Apache-2.0-Lizenz)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
}

// Für die Unit-Tests auf dem Rechner: PDFBox lädt Schriftdaten auf dem Handy aus den
// Android-Assets. In normalen JVM-Tests gibt es die nicht, deshalb hier auspacken und als
// Test-Ressourcen bereitstellen. Betrifft nur Tests, nicht die App.
val pdfboxTestResources = tasks.register<Sync>("pdfboxTestResources") {
    val aar = configurations.detachedConfiguration(
        dependencies.create("com.tom-roush:pdfbox-android:2.0.27.0@aar")
    )
    from({ aar.files.map { zipTree(it) } }) {
        include("assets/**")
        eachFile { path = path.removePrefix("assets/") }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("pdfbox-test-resources"))
}
android.sourceSets.getByName("test").resources.srcDir(layout.buildDirectory.dir("pdfbox-test-resources"))
tasks.matching { it.name.startsWith("process") && it.name.endsWith("UnitTestJavaRes") }
    .configureEach { dependsOn(pdfboxTestResources) }

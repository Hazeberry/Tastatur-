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
        // Nur ARM: das sind alle Handys. x86 bräuchten nur Emulatoren und würde die APK
        // wegen der Texterkennung um rund 15 MB vergrößern.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        // Native Bibliotheken komprimiert in die APK (kleinerer Download).
        jniLibs.useLegacyPackaging = true
        // Datentabellen für Post-Quanten-Kryptografie aus BouncyCastle; PDFBox nutzt sie nie.
        resources.excludes += "org/bouncycastle/pqc/**"
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
        // Für Robolectric: Manifest, Ressourcen und Assets der App in den Tests verfügbar
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // PDF-Text lesen, komplett offline (Apache PDFBox für Android, Apache-2.0-Lizenz)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Verschlüsselte ZIPs (Apache-2.0)
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    // 7z, tar, gz, bz2, xz (Apache-2.0); xz für 7z/LZMA
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    // Texterkennung für eingescannte PDFs und Bilder, offline (Apache-2.0)
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // Bilddrehung (EXIF) zuverlässig lesen, auch bei HEIC (Apache-2.0)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
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

// Robolectric braucht unter Java 17 Zugriff auf FileDescriptor (für ParcelFileDescriptor).
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
}

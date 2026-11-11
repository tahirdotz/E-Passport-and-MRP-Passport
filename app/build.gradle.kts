plugins {
    id("com.android.application")
}

android {
    namespace = "com.passportreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.passportreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Phones are ARM; x86_64 is kept only so the UI can be opened in the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            // Signed with the debug key so you can install a release build on your own phone.
            signingConfig = signingConfigs.getByName("debug")
            // BouncyCastle and JMRTD rely on reflection; shrinking them is not worth the risk here.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = false
    }

    packaging {
        resources {
            // BouncyCastle, JMRTD and SCUBA ship overlapping metadata files.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity:1.13.0")

    // Camera
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // On-device OCR (bundled model, works offline) and face detection for chipless passports
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:face-detection:16.1.7")

    // ePassport chip (ICAO 9303): JMRTD + SCUBA Android card service + BouncyCastle
    implementation("org.jmrtd:jmrtd:0.8.9")
    implementation("net.sf.scuba:scuba-sc-android:0.0.27")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")

    // JPEG2000 decoder (OpenJPEG) – many passport chips store the photo as JPEG2000
    implementation("dev.keiji.jp2:jp2-android:1.0.5")

    testImplementation("junit:junit:4.13.2")
}

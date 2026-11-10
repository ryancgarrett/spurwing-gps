plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The GitHub build passes these in. Every build must be signed with the same key,
// or the phone refuses to install it as an update over the previous version.
val keystoreFile: String? = System.getenv("SIGNING_KEYSTORE_FILE")
// Each GitHub build gets a higher number so the phone sees it as newer.
val buildNumber: Int = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.spurwing.gps"
    compileSdk = 35

    defaultConfig {
        // The app's permanent ID on Google Play, under your own domain.
        applicationId = "com.dontnibble.spurwinggps"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "spurwing"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Real key on GitHub; the debug key for a local build in Android Studio.
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // Lint warnings shouldn't stop a build from reaching the phone.
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    testImplementation("junit:junit:4.13.2")
}

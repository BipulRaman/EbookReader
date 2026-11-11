plugins {
    id("com.android.application") version "9.4.1"
}

android {
    namespace = "in.bipul.ebookreader"
    compileSdk = 37

    defaultConfig {
        // Never change: a different applicationId is a different app, so installs can't upgrade in place.
        applicationId = "in.bipul.ebookreader"
        minSdk = 26
        targetSdk = 37
        // CI passes -PversionCode / -PversionName from the release tag (1.2.3 -> 10203); local
        // builds use these defaults.
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("1.0.0-dev")
    }

    signingConfigs {
        // CI decodes the stable keystore from the DEBUG_KEYSTORE_BASE64 secret and points
        // EBOOKREADER_KEYSTORE at it, so every release is signed with the same key (in-place
        // upgrades). Without it, release builds fall back to the local debug key so they still install.
        create("release") {
            val keystore = providers.environmentVariable("EBOOKREADER_KEYSTORE").orNull
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("EBOOKREADER_KEYSTORE_PASSWORD").getOrElse("android")
                keyAlias = providers.environmentVariable("EBOOKREADER_KEY_ALIAS").getOrElse("androiddebugkey")
                keyPassword = providers.environmentVariable("EBOOKREADER_KEY_PASSWORD").getOrElse("android")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The bundled reader font is streamed straight out of the APK into the WebView.
        noCompress += "ttf"
    }

    packaging {
        resources {
            // Kotlin reflection metadata; nothing in the app uses reflection.
            excludes += listOf("kotlin/**", "META-INF/*.kotlin_module", "META-INF/*.version", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        // BuildConfig.VERSION_NAME is shown in the library's About dialog.
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's Android 16 sandbox reaches into JDK internals (FileDescriptor access).
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

dependencies {
    // The library grid (a "Continue reading" header spanning the cover grid).
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

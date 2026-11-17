plugins {
    id("com.android.application")
}

android {
    namespace = "com.meetwake.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.meetwake.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideload build: signed with the local debug key so it installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // No runtime libraries on purpose: framework APIs only, to keep the APK tiny.
    testImplementation("junit:junit:4.13.2")
}

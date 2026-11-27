plugins {
    id("com.android.application")
}

android {
    namespace = "com.rootcore.r8test"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rootcore.r8test"
        minSdk = 29
        targetSdk = 35
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":root-core"))
}

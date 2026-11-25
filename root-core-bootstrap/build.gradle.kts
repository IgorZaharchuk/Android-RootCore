plugins {
    id("com.android.application")
}

android {
    namespace = "com.rootcore.bootstrap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rootcore.bootstrap"
        minSdk = 29
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

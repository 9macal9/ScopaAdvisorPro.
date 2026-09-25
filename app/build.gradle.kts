plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.scopaadvisor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.scopaadvisor"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }
}

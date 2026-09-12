plugins {
    id("com.android.application")
}

android {
    namespace = "com.ffsagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ffsagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
        }
    }
}

kotlin { jvmToolchain(17) }

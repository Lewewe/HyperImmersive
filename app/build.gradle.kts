plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.os4.immersive"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.yzc26623.HyperImmersive"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1-proto"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Same identity as HyperMusicCover-Enhanced, which the extension will talk to.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

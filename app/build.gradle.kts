plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.whatsappfeatureguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.whatsappfeatureguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "featureSet"

    productFlavors {
        create("channelsOnly") {
            dimension = "featureSet"
            buildConfigField("boolean", "BLOCK_STATUS", "false")
            buildConfigField("boolean", "BLOCK_CHANNELS", "true")
            buildConfigField("boolean", "CUSTOM_TESTING", "false")
            resValue(
                "string",
                "app_name",
                "WhatsApp Feature Guard - Channels Only"
            )
        }

        create("statusAndChannels") {
            dimension = "featureSet"
            buildConfigField("boolean", "BLOCK_STATUS", "true")
            buildConfigField("boolean", "BLOCK_CHANNELS", "true")
            buildConfigField("boolean", "CUSTOM_TESTING", "false")
            resValue(
                "string",
                "app_name",
                "WhatsApp Feature Guard - Status + Channels"
            )
        }

        create("customTesting") {
            dimension = "featureSet"
            buildConfigField("boolean", "BLOCK_STATUS", "false")
            buildConfigField("boolean", "BLOCK_CHANNELS", "false")
            buildConfigField("boolean", "CUSTOM_TESTING", "true")
            resValue(
                "string",
                "app_name",
                "WhatsApp Feature Guard - Custom Testing"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}

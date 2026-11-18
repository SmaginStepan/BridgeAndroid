plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.an0obis.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.an0obis.bridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"

        // ScoreServer credentials for this game live in ~/.gradle/gradle.properties (never in git):
        //   scoreApiKey=...   scoreSecret=...
        // Without them the build works, but score/season calls are rejected by the server.
        val scoreApiKey = providers.gradleProperty("scoreApiKey").orNull ?: ""
        val scoreSecret = providers.gradleProperty("scoreSecret").orNull ?: ""
        buildConfigField("String", "SCORE_API_KEY", "\"$scoreApiKey\"")
        buildConfigField("String", "SCORE_SECRET", "\"$scoreSecret\"")
    }

    // Real AdMob ids live in ~/.gradle/gradle.properties (never in git):
    //   admobAppId=ca-app-pub-XXXXXXXXXXXXXXXX~NNNNNNNNNN
    //   admobInterstitialId=ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN
    // Debug builds use Google's test ids. A release build without real ids
    // ships with ads switched off, so test ads can never reach players.
    val testAppId = "ca-app-pub-3940256099942544~3347511713"
    val testInterstitialId = "ca-app-pub-3940256099942544/1033173712"
    val realAppId = providers.gradleProperty("admobAppId").orNull
    val realInterstitialId = providers.gradleProperty("admobInterstitialId").orNull

    // Google Play upload key, kept outside the repo. In ~/.gradle/gradle.properties:
    //   bridgeStoreFile=C:/path/to/the.keystore
    //   bridgeStorePassword=...
    //   bridgeKeyAlias=...
    //   bridgeKeyPassword=...
    // Without all four the release build is simply left unsigned.
    val uploadStore = providers.gradleProperty("bridgeStoreFile").orNull
    val uploadStorePassword = providers.gradleProperty("bridgeStorePassword").orNull
    val uploadAlias = providers.gradleProperty("bridgeKeyAlias").orNull
    val uploadKeyPassword = providers.gradleProperty("bridgeKeyPassword").orNull
    signingConfigs {
        if (uploadStore != null && uploadStorePassword != null && uploadAlias != null && uploadKeyPassword != null) {
            create("release") {
                storeFile = file(uploadStore)
                storePassword = uploadStorePassword
                keyAlias = uploadAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ADS_ENABLED", "true")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$testInterstitialId\"")
            manifestPlaceholders["admobAppId"] = testAppId
        }
        release {
            val adsReady = realAppId != null && realInterstitialId != null
            buildConfigField("boolean", "ADS_ENABLED", adsReady.toString())
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${realInterstitialId ?: testInterstitialId}\"")
            manifestPlaceholders["admobAppId"] = realAppId ?: testAppId
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.ads)
    implementation(libs.ump)
    implementation(libs.billing.ktx)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
}

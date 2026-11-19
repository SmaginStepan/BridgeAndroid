import java.util.Properties

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
        versionCode = 5
        versionName = "0.3.2"

        // ScoreServer credentials live in the root's gitignored score-secrets.properties,
        // or in ~/.gradle/gradle.properties / -P properties for CI (never in git):
        //   scoreApiKey=...   scoreSecret=...
        // Without them the build works, but score/season calls are rejected by the server.
        val scoreSecrets = Properties().apply {
            rootProject.file("score-secrets.properties").takeIf { it.isFile }
                ?.inputStream()?.use { load(it) }
        }
        val scoreApiKey = providers.gradleProperty("scoreApiKey").orNull
            ?: scoreSecrets.getProperty("scoreApiKey", "")
        val scoreSecret = providers.gradleProperty("scoreSecret").orNull
            ?: scoreSecrets.getProperty("scoreSecret", "")
        buildConfigField("String", "SCORE_API_KEY", "\"$scoreApiKey\"")
        buildConfigField("String", "SCORE_SECRET", "\"$scoreSecret\"")
    }

    // Real AdMob ids live in ~/.gradle/gradle.properties (never in git), under
    // Bridge's own names: that file is shared with Preferans, whose ids are
    // admobAppId / admobInterstitialId and must never end up in this app.
    //   bridgeAdmobAppId=ca-app-pub-XXXXXXXXXXXXXXXX~NNNNNNNNNN
    //   bridgeAdmobInterstitialId=ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN
    // Debug builds use Google's test ids. A release build without real ids
    // ships with ads switched off, so test ads can never reach players.
    val testAppId = "ca-app-pub-3940256099942544~3347511713"
    val testInterstitialId = "ca-app-pub-3940256099942544/1033173712"
    val realAppId = providers.gradleProperty("bridgeAdmobAppId").orNull?.trim()?.takeIf { it.isNotEmpty() }
    val realInterstitialId = providers.gradleProperty("bridgeAdmobInterstitialId").orNull?.trim()?.takeIf { it.isNotEmpty() }
    // a wrong or placeholder value would switch ads on with an invalid id, and an invalid app id
    // crashes the app at launch; the messages never print the value
    if (realAppId != null && !Regex("ca-app-pub-\\d{16}~\\d{10}").matches(realAppId)) {
        throw GradleException("bridgeAdmobAppId is not an AdMob app id (ca-app-pub-<16 digits>~<10 digits>)")
    }
    if (realInterstitialId != null && !Regex("ca-app-pub-\\d{16}/\\d{10}").matches(realInterstitialId)) {
        throw GradleException("bridgeAdmobInterstitialId is not an AdMob ad unit id (ca-app-pub-<16 digits>/<10 digits>)")
    }
    // a copy of the Preferans ids under the Bridge names would send Bridge's ads to the wrong AdMob app
    if (realAppId != null && realAppId == providers.gradleProperty("admobAppId").orNull) {
        throw GradleException("bridgeAdmobAppId is the Preferans AdMob app id: create a Bridge app in AdMob")
    }
    if (realInterstitialId != null && realInterstitialId == providers.gradleProperty("admobInterstitialId").orNull) {
        throw GradleException("bridgeAdmobInterstitialId is the Preferans ad unit: create a Bridge ad unit in AdMob")
    }

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

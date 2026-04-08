import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")

}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.example.aiguru"
    compileSdk = 36
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        applicationId = "com.example.aiguru"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val paymentBaseUrl = localProperties["PAYMENT_BASE_URL"] as? String ?: ""
        val razorpayKeyId  = localProperties["RAZORPAY_KEY_ID"]  as? String ?: ""
        buildConfigField("String", "PAYMENT_BASE_URL", "\"$paymentBaseUrl\"")
        buildConfigField("String", "RAZORPAY_KEY_ID",  "\"$razorpayKeyId\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProperties["RELEASE_STORE_FILE"] as? String ?: "keystore.jks")
            storePassword = localProperties["RELEASE_STORE_PASSWORD"] as? String ?: ""
            keyAlias = localProperties["RELEASE_KEY_ALIAS"] as? String ?: ""
            keyPassword = localProperties["RELEASE_KEY_PASSWORD"] as? String ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Gemini Live uses server proxy — no key needed in APK
        }
        debug {
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("com.razorpay:checkout:1.6.40")
    implementation("com.google.firebase:firebase-auth:23.1.0")
    implementation("com.google.firebase:firebase-firestore:25.1.1")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    
    // Markwon — rich markdown rendering in chat (tables, strikethrough, code, headings)
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")

    // RecyclerView & Material Design 3
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    
    // Voice & Audio
    implementation("androidx.camera:camera-core:1.3.4")
    
    // Image handling
    implementation("com.squareup.picasso:picasso:2.8")
    
    // Image crop + zoom (UCrop)
    implementation("com.github.yalantis:ucrop:2.2.8")
    
    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    
    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    
    // PDF handling
    implementation("com.itextpdf:itextpdf:5.5.13.3")
    
    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation(libs.androidx.core.animation)
    implementation(libs.androidx.compose.remote.creation.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(platform("com.google.firebase:firebase-bom:34.11.0"))
}
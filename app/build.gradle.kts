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
    namespace = "com.aiguruapp.student"
    compileSdk = 36
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        applicationId = "com.aiguruapp.student"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.2.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Server URL, Razorpay key, and payment URL are fetched from Firestore admin_config/global
        // at runtime via AdminConfigRepository — no compile-time keys needed.
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProperties["RELEASE_STORE_FILE"] as? String
            val storePass     = localProperties["RELEASE_STORE_PASSWORD"] as? String
            val keyAliasVal   = localProperties["RELEASE_KEY_ALIAS"] as? String
            val keyPass       = localProperties["RELEASE_KEY_PASSWORD"] as? String

            // Fail loudly at configuration time if any release signing value is missing.
            // This prevents Gradle from silently falling back to the debug keystore
            // and producing a debug-signed AAB that Google Play will reject.
            require(!storeFilePath.isNullOrBlank()) {
                "RELEASE_STORE_FILE is missing from local.properties. " +
                "Add it before building a release APK/AAB."
            }
            require(!storePass.isNullOrBlank())   { "RELEASE_STORE_PASSWORD missing from local.properties" }
            require(!keyAliasVal.isNullOrBlank()) { "RELEASE_KEY_ALIAS missing from local.properties" }
            require(!keyPass.isNullOrBlank())     { "RELEASE_KEY_PASSWORD missing from local.properties" }

            storeFile     = file(storeFilePath)
            storePassword = storePass
            keyAlias      = keyAliasVal
            keyPassword   = keyPass
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
            // Upload full debug symbols so crash stack traces are human-readable in Play Console
            ndk {
                debugSymbolLevel = "FULL"
            }
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

    // Required for Android 15+ 16 KB memory page size support.
    // Native .so files must be embedded uncompressed so the OS can map them
    // directly at the correct page-aligned offset.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}


dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("com.razorpay:checkout:1.6.41")
    implementation("com.google.firebase:firebase-auth:23.1.0")
    implementation("com.google.firebase:firebase-firestore:25.1.1")
    implementation("com.google.firebase:firebase-storage:21.1.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    
    // Markwon — rich markdown rendering in chat (tables, strikethrough, code, headings)
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:inline-parser:4.6.2") // required by JLatexMathPlugin
    implementation("io.noties.markwon:ext-latex:4.6.2")    // LaTeX math rendering

    // RecyclerView & Material Design 3
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    
    // Voice & Audio
    implementation("androidx.camera:camera-core:1.3.4")
    
    // Image handling
    implementation("com.squareup.picasso:picasso:2.8")
    
    // Image crop + zoom (UCrop) — 2.2.9+ is built with 16 KB-aligned native libs
    implementation("com.github.yalantis:ucrop:2.2.9")
    
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
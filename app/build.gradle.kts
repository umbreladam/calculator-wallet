import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val signProps = Properties().apply { val f = rootProject.file("keystore.properties"); if (f.exists()) f.inputStream().use { load(it) } }
android {
    namespace = "calc.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.simple.calculator"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("boolean", "ALLOW_SCREENSHOTS", (project.findProperty("allowScreenshots") ?: "false").toString())
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    signingConfigs {
        create("release") {
            if (signProps.isNotEmpty()) {
                storeFile = rootProject.file(signProps.getProperty("storeFile"))
                storePassword = signProps.getProperty("storePassword")
                keyAlias = signProps.getProperty("keyAlias")
                keyPassword = signProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module", "bip39-english.txt.bak") } ; jniLibs { useLegacyPackaging = true } }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
dependencies {
    implementation(project(":core")) { exclude(group = "org.bitcoindevkit") }
    implementation("org.bitcoindevkit:bdk-android:3.1.1")
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    val cam = "1.3.4"
    implementation("androidx.camera:camera-camera2:$cam")
    implementation("androidx.camera:camera-lifecycle:$cam")
    implementation("androidx.camera:camera-view:$cam")
    implementation("com.google.zxing:core:3.5.3")
}

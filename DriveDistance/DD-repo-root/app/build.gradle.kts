plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.drivedistance"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.drivedistance"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.osmdroid:osmdroid-android:6.1.18")   // OpenStreetMap map, no API key
}

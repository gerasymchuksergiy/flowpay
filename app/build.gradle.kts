plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeyPath = System.getenv("FLOWPAY_KEYSTORE_PATH")
val releaseKeyPassword = System.getenv("FLOWPAY_KEYSTORE_PASSWORD")

android {
 namespace = "com.flowpay.app"
 compileSdk = 36

 defaultConfig {
  applicationId = "com.flowpay.app"
  minSdk = 26
  targetSdk = 36
  versionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
  versionName = providers.gradleProperty("versionName").orNull ?: "1.0.0"
 }

 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { compose = true; buildConfig = true }

 signingConfigs {
  if (!releaseKeyPath.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
   create("flowPayRelease") {
    storeFile = file(releaseKeyPath)
    storePassword = releaseKeyPassword
    keyAlias = "flowpay"
    keyPassword = releaseKeyPassword
   }
  }
 }
 buildTypes {
  getByName("release") {
   isMinifyEnabled = true
   isShrinkResources = true
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
   if (signingConfigs.names.contains("flowPayRelease")) {
    signingConfig = signingConfigs.getByName("flowPayRelease")
   }
  }
 }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2026.05.00"))
 implementation("androidx.core:core-ktx:1.18.0")
 implementation("androidx.activity:activity-compose:1.13.0")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("androidx.work:work-runtime-ktx:2.11.2")
 testImplementation("junit:junit:4.13.2")
 // org.json ships in the Android SDK as stubs that throw in unit tests, so the
 // real implementation is needed to exercise the Monobank feed parsing.
 testImplementation("org.json:json:20250107")
}


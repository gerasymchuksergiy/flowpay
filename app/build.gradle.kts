plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeyPath = System.getenv("FLOWPAY_KEYSTORE_PATH")
val releaseKeyPassword = System.getenv("FLOWPAY_KEYSTORE_PASSWORD")

// The phone will only accept an update signed with the same certificate as the
// copy already installed, so the alias is configurable rather than fixed: the
// existing install was signed by the local debug keystore, whose alias is
// androiddebugkey, and a release build has to be able to reuse that exact key.
val releaseKeyAlias = System.getenv("FLOWPAY_KEYSTORE_ALIAS") ?: "flowpay"
val releaseKeyStoreType = System.getenv("FLOWPAY_KEYSTORE_TYPE")

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
    keyAlias = releaseKeyAlias
    keyPassword = releaseKeyPassword
    releaseKeyStoreType?.takeIf { it.isNotBlank() }?.let { storeType = it }
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
 // Arrives transitively through material3, but the shared element transition
 // between a wishlist card and its page depends on it directly.
 implementation("androidx.compose.animation:animation")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("androidx.work:work-runtime-ktx:2.11.2")
 // The home screen widget. Glance is versioned on its own rather than through the
 // Compose BOM, so the version is pinned here.
 implementation("androidx.glance:glance-appwidget:1.1.1")
 testImplementation("junit:junit:4.13.2")
 // org.json ships in the Android SDK as stubs that throw in unit tests, so the
 // real implementation is needed to exercise the Monobank feed parsing.
 testImplementation("org.json:json:20250107")
}


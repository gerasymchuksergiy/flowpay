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

  // Injected by CI from a repository secret, never committed. This repository is
  // public, and GitHub's secret scanning reports a committed key straight to the
  // provider, who revokes it — so a key in the source would stop working rather
  // than merely leak. Empty in a local build, which the app reads as "no
  // assessment available" instead of failing.
  // Kept only if it is one line of characters that cannot break the Java string
  // literal this is pasted into. Deliberately not a check on the key's shape:
  // the first attempt required it to start with "AIza" and would have silently
  // discarded a perfectly good key in Google's other format, which begins
  // "AQ." and carries a dot. Guessing a provider's format is not this build's
  // job; producing valid Java is.
  //
  // The v3.10.0 release failed to compile because the secret held something
  // multi-line with backslashes in it, pasted straight into the literal. A
  // secret that is wrong now yields an empty key, which the app already reads
  // as "no appraisal available", instead of taking the whole release down.
  val geminiKey = (System.getenv("GEMINI_API_KEY") ?: "").trim()
   .takeIf { it.length in 20..200 && it.all { ch -> ch.isLetterOrDigit() || ch in "._-" } }
   .orEmpty()
  buildConfigField("String", "GEMINI_KEY", "\"" + geminiKey + "\"")
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
 // Polygons and the morph between them. Not part of the Compose BOM — it is a
 // plain graphics library with no Compose dependency of its own — so it carries
 // its own version. The AAR declares minSdk 23, below this app's floor of 26.
 implementation("androidx.graphics:graphics-shapes:1.1.0")
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


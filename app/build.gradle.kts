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

 // The screenshot tests need the app's own resources — Inter above all — to draw
 // what the phone draws. Harmless for the plain JVM tests, which never ask.
 testOptions { unitTests.isIncludeAndroidResources = true }

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
 // Already on the classpath through Compose; named here because the app now calls
 // LifecycleEventEffect directly to re-read the store when it comes back to the front.
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
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
 // «Сканувати QR»: Google Play services' own scanner screen — FlowPay asks for no
 // camera permission and gets back only the text that was read. QrScan.kt.
 implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
 // Frosted glass under the navigation bar. Pinned: 1.7.3 and 2.x are built with
 // Kotlin 2.3+ and pull a newer Compose than the BOM, which this project's Kotlin
 // 2.1 cannot read. Below Android 12 it draws a plain tint instead of blurring.
 implementation("dev.chrisbanes.haze:haze:1.7.2")
 implementation("androidx.work:work-runtime-ktx:2.11.2")
 // The home screen widget. Glance is versioned on its own rather than through the
 // Compose BOM, so the version is pinned here.
 implementation("androidx.glance:glance-appwidget:1.1.1")
 testImplementation("junit:junit:4.13.2")
 // org.json ships in the Android SDK as stubs that throw in unit tests, so the
 // real implementation is needed to exercise the Monobank feed parsing.
 testImplementation("org.json:json:20250107")

 // Screenshots without a phone: Robolectric draws the real Compose screens with
 // Android's own renderer on the JVM and Roborazzi saves them as PNG. The versions
 // are pinned on purpose — Roborazzi 1.61+ is built with Kotlin 2.3, which this
 // project's Kotlin 2.1 cannot read. See ScreenShots.kt and HANDOFF §5.
 testImplementation("org.robolectric:robolectric:4.16.1")
 testImplementation("io.github.takahirom.roborazzi:roborazzi:1.60.0")
 testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.60.0")
 testImplementation(platform("androidx.compose:compose-bom:2026.05.00"))
 testImplementation("androidx.compose.ui:ui-test-junit4")
 // The empty activity the compose test rule hosts its content in. Debug only: the
 // release APK, which is what the phone installs, never carries it.
 debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Screenshots run only when asked for, with -Pshots, so CI's testDebugUnitTest and
// every ordinary local run stay as they were. The PNGs land in
// app/build/outputs/roborazzi/.
val shots = providers.gradleProperty("shots").isPresent
tasks.withType<Test>().configureEach {
 if (shots) {
  filter { includeTestsMatching("com.flowpay.app.screens.*") }
  // Without it captureRoboImage compares rather than writes.
  systemProperty("roborazzi.test.record", "true")
  systemProperty("robolectric.pixelCopyRenderMode", "hardware")
  // Robolectric downloads its android-all jar into ~/.m2, and the space in
  // "C:\Users\GS PC" breaks the native renderer on newer SDKs.
  if (System.getProperty("os.name").startsWith("Windows")) {
   systemProperty("maven.repo.local", "C:/Temp/robolectric-m2")
  }
  maxHeapSize = "3g"
 } else {
  exclude("com/flowpay/app/screens/**")
 }
}


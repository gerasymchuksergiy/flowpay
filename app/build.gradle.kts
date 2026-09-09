plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace="com.flowpay.app"; compileSdk=35
 defaultConfig { applicationId="com.flowpay.app"; minSdk=26; targetSdk=35; versionCode=1; versionName="1.0.0" }
 buildFeatures { compose=true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.01.01"))
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("androidx.work:work-runtime-ktx:2.10.0")
}

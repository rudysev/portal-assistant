plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.portal.assistant"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.portal.assistant"
    minSdk = 28          // Android 9 — Portal+ ("aloha")
    targetSdk = 29       // Android 10 — Portal-era behavior
    versionCode = 4
    versionName = "2.2"

    // The Gemini API key is provided at runtime (setup.sh → api_key.txt → prefs, or Settings → API key),
    // not baked in. This blank fallback keeps BuildConfig.GEMINI_API_KEY available as a dev seam.
    buildConfigField("String", "GEMINI_API_KEY", "\"\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
}

dependencies {
  // Shared utilities (DebugLog/PcmLevel/PcmCaptureSession/PcmCaptureFormat); composite-build substituted from ./commons.
  implementation("com.portal:commons")
  // Shared Android shells (AudioRecordPcmDevice); composite-build substituted from ./commons-android.
  implementation("com.portal:commons-android")
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.media)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.kotlinx.coroutines.android)
  // Gemini Live API WebSocket transport (GMS-free; Portal has no built-in WebSocket).
  implementation(libs.okhttp)

  testImplementation(libs.junit)
  // Real org.json for JVM unit tests (android.jar's stubs throw), so LiveClient parsing is testable.
  testImplementation(libs.json)

  debugImplementation(libs.androidx.compose.ui.tooling)
}

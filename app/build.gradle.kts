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
    versionCode = 5
    versionName = "2.3"

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

kotlin {
  // Keep the build warning-free: any compiler warning fails compilation.
  compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
  // Shared utilities (DebugLog/PcmLevel/PcmCaptureSession/PcmCaptureFormat); composite-build substituted from ./commons.
  implementation("com.portal:commons")
  // Shared Android shells (AudioRecordPcmDevice); composite-build substituted from ./commons-android.
  implementation("com.portal:commons-android")
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.media)
  implementation(libs.androidx.compose.animation)
  implementation(libs.androidx.compose.animation.core)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.foundation.layout)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons)
  implementation(libs.androidx.compose.runtime)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.geometry)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.text)
  implementation(libs.androidx.compose.ui.unit)
  // NotificationCompat/ContextCompat; the app uses none of core-ktx's Kotlin extensions.
  implementation(libs.androidx.core)
  implementation(libs.androidx.lifecycle.common)
  implementation(libs.androidx.lifecycle.viewmodel)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.kotlinx.coroutines.android)
  // Gemini Live API WebSocket transport (GMS-free; Portal has no built-in WebSocket).
  implementation(libs.okhttp)

  testImplementation(libs.junit)
  testImplementation(platform(libs.androidx.compose.bom))
  testImplementation(libs.androidx.compose.animation.core) // FrameClockSpringTest reads Spring.StiffnessMedium
  // Real org.json for JVM unit tests (android.jar's stubs throw), so the wire formats are testable.
  testImplementation(libs.json)

  debugImplementation(libs.androidx.compose.ui.tooling)
}

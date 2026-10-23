plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlinter)
  alias(libs.plugins.detekt)
  alias(libs.plugins.dependency.analysis) // `buildHealth` advice; see the root build
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.portal.assistant"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.portal.assistant"
    minSdk = 28 // Android 9 — Portal+ ("aloha")
    // Sideloaded onto Portal (no Play Store), so Play's target-API floor doesn't apply; 29 is deliberate.
    //noinspection ExpiredTargetSdkVersion
    targetSdk = 29 // Android 10 — Portal-era behavior
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
  // Any lint warning fails the build. No baseline: suppress inline only, with a reason.
  lint {
    warningsAsErrors = true
    abortOnError = true
    checkReleaseBuilds = true
    // Dependency-freshness checks query Maven/Google at lint time, so the result changes whenever upstream ships a
    // release, not when this code changes. Dependency updates are deliberate (Renovate/Dependabot are out of scope).
    disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
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

// `check` lints only the default (debug) variant. Lint release too, so release-variant problems fail pre-push and CI
// rather than waiting for lintVital, which only runs on assembleRelease (checkReleaseBuilds) and nothing builds that.
tasks.check { dependsOn("lintRelease") }

dependencies {
  // Shared utilities (DebugLog/PcmLevel/PcmCaptureSession/PcmCaptureFormat); composite-build substituted from
  // ./commons.
  implementation(libs.portal.commons)
  // Shared Android shells (AudioRecordPcmDevice); composite-build substituted from ./commons-android.
  implementation(libs.portal.commons.android)
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
  // NotificationCompat/ContextCompat.
  implementation(libs.androidx.core)
  // SharedPreferences.edit {} and String.toUri() (Android Lint's UseKtx).
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.common)
  implementation(libs.androidx.lifecycle.viewmodel)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.kotlinx.coroutines.core)
  // Runtime-only: supplies Dispatchers.Main on Android; the code itself only uses kotlinx-coroutines-core APIs.
  runtimeOnly(libs.kotlinx.coroutines.android)
  // Gemini Live API WebSocket transport (GMS-free; Portal has no built-in WebSocket).
  implementation(libs.okhttp)
  implementation(libs.okio) // ByteString frames on the WebSocket

  testImplementation(libs.junit)
  testImplementation(platform(libs.androidx.compose.bom))
  testImplementation(libs.androidx.compose.animation.core) // FrameClockSpringTest reads Spring.StiffnessMedium
  testImplementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.okio)
  // Real org.json for JVM unit tests (android.jar's stubs throw), so the wire formats are testable.
  testImplementation(libs.json)

  debugImplementation(libs.androidx.compose.ui.tooling)

  // Jetpack Compose rules for detekt (portal-assistant is the only Compose app).
  detektPlugins(libs.compose.rules.detekt)
}

// detekt on the defaults plus the shared opt-ins in config/detekt/detekt.yml (identical in all three repos).
detekt {
  buildUponDefaultConfig = true
  config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// `check` runs the type-resolving tasks (detektMain/detektTest: main and test classes, every variant) instead of the
// plugin's default `detekt` task, which analyses the same sources without type resolution. `detekt` is disabled
// rather than unhooked from `check`, so this holds however the plugin wires it.
tasks.named("detekt") { enabled = false }
tasks.check { dependsOn("detektMain", "detektTest") }

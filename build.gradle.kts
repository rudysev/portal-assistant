plugins {
  alias(libs.plugins.android.application) apply false
  // Dependency advice (`./gradlew buildHealth`), run as its own CI job and not part of `check`. :app applies it too
  // (no auto-apply from the root).
  alias(libs.plugins.dependency.analysis)
  alias(libs.plugins.kotlin.compose) apply false
}

dependencyAnalysis {
  issues {
    all {
      onAny { severity("fail") }
    }
  }
}

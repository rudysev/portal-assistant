plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  // ktlint via kotlinter (ADR 0001), applied here for the Gradle-script lint below.
  alias(libs.plugins.kotlinter)
  // Dependency advice (`./gradlew buildHealth`), run as its own CI job and not part of `check`. :app applies it too
  // (no auto-apply from the root).
  alias(libs.plugins.dependency.analysis)
  base
}

// kotlinter's lintKotlin only covers source sets, so lint the Gradle scripts here (the pre-commit hook does too).
val lintKotlinScripts by tasks.registering(org.jmailen.gradle.kotlinter.tasks.LintTask::class) {
  source(fileTree(rootDir) { include("*.gradle.kts", "*/*.gradle.kts") })
}
tasks.check { dependsOn(lintKotlinScripts) }

dependencyAnalysis {
  issues {
    all {
      onAny { severity("fail") }
    }
  }
}

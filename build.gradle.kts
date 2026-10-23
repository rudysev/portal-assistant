plugins {
  alias(libs.plugins.android.application) apply false
  // Only puts KGP `libs.versions.kotlin` on the build classpath, overriding the older KGP that AGP's built-in Kotlin
  // depends on. Never applied: AGP 9 registers the Kotlin support itself.
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.compose) apply false
  // ktlint via kotlinter (ADR 0001), applied here for the Gradle-script lint below.
  alias(libs.plugins.kotlinter)
  // Dependency advice (`./gradlew buildHealth`), run as its own CI job and not part of `check`. :app applies it too
  // (no auto-apply from the root).
  alias(libs.plugins.dependency.analysis)
  base
}

// kotlinter's lintKotlin only covers source sets, so lint the Gradle scripts here (the pre-commit hook does too).
val lintKotlinScripts = tasks.register<org.jmailen.gradle.kotlinter.tasks.LintTask>("lintKotlinScripts") {
  source(fileTree(rootDir) { include("*.gradle.kts", "*/*.gradle.kts") })
}
tasks.check { dependsOn(lintKotlinScripts) }
val formatKotlinScripts = tasks.register<org.jmailen.gradle.kotlinter.tasks.FormatTask>("formatKotlinScripts") {
  source(fileTree(rootDir) { include("*.gradle.kts", "*/*.gradle.kts") })
}
// The root has no formatKotlin of its own, so `./gradlew formatKotlin` also fixes the Gradle scripts.
tasks.register("formatKotlin") { dependsOn(formatKotlinScripts) }

dependencyAnalysis {
  issues {
    all {
      onAny { severity("fail") }
    }
  }
}

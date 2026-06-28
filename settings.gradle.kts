pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "Portal Assistant"

// Shared library (DebugLog/PcmLevel/PcmCaptureSession) consumed via composite build — its single source lives
// in the sibling ../portal-commons (pinned by the portal-apps workspace superproject).
// `implementation("com.portal:commons")` in app/build.gradle.kts is substituted with this included build.
includeBuild("../portal-commons")

include(":app")

pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }

  resolutionStrategy {
    eachPlugin {
      if (requested.id.id == "org.jetbrains.kotlinx.schema.ksp") {
        useModule("org.jetbrains.kotlinx:kotlinx-schema-gradle-plugin:0.0.3")
      }
    }
  }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "core-service"

include("commons")
include("control")
include("git")
include("lab")
include("local")
include("openai-client")
include("opencode-client")
include("opencode-enclosed")
include("shared")
include("worker")

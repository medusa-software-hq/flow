plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "core-service"

include("commons")
include("control")
include("git")
include("opencode-client")
include("opencode-enclosed")
include("shared")
include("worker")
include("local")

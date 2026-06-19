plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "flow"

include(
    ":cli",
    ":engine:harness",
    ":engine:toolchains:gradle",
    ":engine:toolchains:nodejs",
    ":engine:virtual-editor",
)

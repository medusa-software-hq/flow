plugins {
  alias(libs.plugins.jib) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.protobuf) apply false
  alias(libs.plugins.sqldelight) apply false
  alias(libs.plugins.versionCatalogUpdate)
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.detekt) apply false
}

val kotlinJvmPluginId = libs.plugins.kotlin.jvm.get().pluginId
val detektPluginId = libs.plugins.detekt.get().pluginId
val ktfmtPluginId = libs.plugins.ktfmt.get().pluginId

// Java 21 is the current broadly adopted LTS
val usedJavaVersion = 21

allprojects {
  repositories {
    // Virtually all modules need Maven Central dependencies
    mavenCentral()

    maven {
      url = uri("https://dl.cloudsmith.io/public/medusa-software/public/maven/")
    }
  }
}

subprojects {
  // Configure Kotlin/JVM modules.
  pluginManager.withPlugin(kotlinJvmPluginId) {
    // Apply Kotlin formatting and static analysis plugins.
    pluginManager.apply(ktfmtPluginId)
    pluginManager.apply(detektPluginId)

    tasks.named("check") {
      // Run formatting checks as part of the standard verification lifecycle.
      dependsOn(tasks.named("ktfmtCheck"))
    }

    extensions.configure<JavaPluginExtension> {
      toolchain {
        // Use a consistent Java toolchain version across local and CI builds.
        languageVersion = JavaLanguageVersion.of(usedJavaVersion)
      }
    }

    tasks.withType<JavaCompile>().configureEach {
      // Preserve parameter names in bytecode for runtime reflection.
      options.compilerArgs.add("-parameters")
    }
  }

  tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Cap forked test-JVM heaps. Without an explicit limit each fork inherits the JVM's ergonomic
    // default of 25% of visible RAM — ~7.6 GB inside the hosted worker's container, which runs with
    // no cgroup memory limit and so sees the whole 30 GB VM. The worker now runs two engine builds
    // concurrently (the parallel Claude/built-in fan-out), so a handful of these forks balloon past
    // the VM's memory and OOM the box mid-build. Tests need very little heap; 1 GB is ample, and it
    // is the forks — not compilation — that were the unbounded consumer (measured).
    maxHeapSize = "1g"
  }
}

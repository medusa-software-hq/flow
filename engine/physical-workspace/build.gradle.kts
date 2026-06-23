plugins { alias(libs.plugins.kotlin.jvm) }

val integrationTestSourceSetName = "integrationTest"

repositories {
  // The Gradle Tooling API is published to Gradle's own repository, not Maven Central.
  maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
}

dependencies {
  api(project(":engine:toolchains:gradle"))
  api(project(":engine:toolchains:nodejs"))

  api(libs.medusa.commons.unix.filesystem)

  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.kotlin.test)
}

sourceSets {
  val main by getting

  create(integrationTestSourceSetName) {
    kotlin.srcDir("src/$integrationTestSourceSetName/kotlin")

    compileClasspath += main.output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
  }
}

dependencies {
  "${integrationTestSourceSetName}Implementation"(project(":engine:test-utils"))
  "${integrationTestSourceSetName}Implementation"(libs.medusa.commons.system)
  "${integrationTestSourceSetName}Implementation"(libs.kotlinx.coroutines.test)
}

tasks.register<Test>(integrationTestSourceSetName) {
  description = "Runs integration tests (allocate a real workspace and drive the toolchains)."
  group = "verification"

  testClassesDirs = sourceSets[integrationTestSourceSetName].output.classesDirs
  classpath = sourceSets[integrationTestSourceSetName].runtimeClasspath
}

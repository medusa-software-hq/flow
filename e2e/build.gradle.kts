// `java` in a build script resolves to the Java plugin extension, shadowing the package.
import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins { alias(libs.plugins.kotlin.jvm) }

repositories {
  // The Gradle Tooling API (pulled in transitively via :engine:harness) is published to Gradle's
  // own repository, not Maven Central.
  maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
}

val integrationTestSourceSetName = "integrationTest"

// This module holds no production code: it exists purely to orchestrate the whole system —
// control plane, GitHub stub, bare-repo remote and the *shipped* worker binary — against a real
// model. Everything lives in the integrationTest source set, so `check` never runs it: it costs
// real model credits and needs ENGINE_TESTS_OPENROUTER_API_KEY.
sourceSets {
  create(integrationTestSourceSetName) {
    kotlin.srcDir("src/$integrationTestSourceSetName/kotlin")
    // No resources.srcDir here: creating the source set already registers
    // src/integrationTest/resources by convention, and adding it again makes every fixture file a
    // duplicate entry, which fails processIntegrationTestResources.

    compileClasspath += configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
  }
}

dependencies {
  // The control plane, booted in-process with the *real* GitHub clients pointed at the stub.
  testImplementation(project(":backend:api:shared"))
  // GitHubAppConfig, for wiring the in-process control plane's App clients at the stub.
  testImplementation(project(":github-app-auth"))
  // FakeGitHubServer, BareRepoFixture, FakeGitHubAppKey.
  testImplementation(project(":test-fixtures:github-stub"))
  // withMaterializedResource, for seeding the bare repo from the fixture resources.
  testImplementation(project(":engine:test-utils"))
  // The scripted engine's public contract (behaviour names, diagnostic text) the sad paths assert
  // on.
  testImplementation(project(":engine:harness"))

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}

// The artifact under test is the shipped fat jar, launched as a subprocess — not a re-wired
// composition root. Its path is handed to the test rather than hardcoded there.
val cliShadowJarTaskPath = ":cli:shadowJar"

tasks.register<Test>(integrationTestSourceSetName) {
  description =
      "Runs the hermetic full-loop test: real server, real shipped worker binary, real git, " +
          "real engine on cheap models; only GitHub is a local stub. Needs OPENROUTER_API_KEY."
  group = "verification"

  dependsOn(cliShadowJarTaskPath)

  testClassesDirs = sourceSets[integrationTestSourceSetName].output.classesDirs
  classpath = sourceSets[integrationTestSourceSetName].runtimeClasspath

  // shadowJar pins archiveBaseName/classifier/version, so the artifact name is stable.
  systemProperty(
      "flow.cli.jar",
      rootProject.layout.projectDirectory.file("cli/build/libs/flow-cli.jar").asFile.absolutePath,
  )

  // A real model call per delegation, plus nested Gradle builds for the fixture's checks.
  timeout = Duration.ofMinutes(20)

  // This harness will be the first thing an engine change breaks, and it must say *why* — Gradle's
  // default reporter prints only the failing class/line, which is next to useless remotely.
  testLogging {
    events("failed", "passed")
    exceptionFormat = TestExceptionFormat.FULL
    showStandardStreams = true
  }
}

// :engine:harness (pulled in for the sad-path assertions) drags in a conflicting slf4j-api; pin it
// to the same version the CLI and harness modules force.
configurations.configureEach { resolutionStrategy { force("org.slf4j:slf4j-api:2.0.17") } }

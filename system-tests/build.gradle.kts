import com.google.protobuf.gradle.id
import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.protobuf)
}

// Typed proto clients against a *deployed* staging environment. This module has no production code:
// its `main` source set holds only the generated gRPC stubs (regenerated from the shared repo-root
// protos, the worker pattern), and every actual test lives in a dedicated `systemTest` source set
// that `check` never runs — it needs a deployed staging API + SPA and WIF/ADC credentials, and (for
// the loop tier) a live worker and real model credits. It runs in the promotion gate and ad hoc
// from a developer machine, via the `systemTest` task below.
sourceSets { main { proto { srcDir(rootDir.resolve("proto")) } } }

val systemTestSourceSetName = "systemTest"

sourceSets {
  create(systemTestSourceSetName) {
    kotlin.srcDir("src/$systemTestSourceSetName/kotlin")
    // Compile against the generated stubs (main output) plus the test dependencies declared below
    // (reusing the `test` configuration's classpath, the e2e-module pattern).
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
  }
}

dependencies {
  // Runtimes the generated proto/gRPC stubs (main) need, plus the typed Armeria gRPC client and
  // ADC ID-token auth — the same client construction the worker uses (WrkGrpcApiClient).
  implementation(platform(libs.grpc.bom))
  implementation(platform(libs.armeria.bom))
  implementation(libs.grpc.protobuf)
  implementation(libs.grpc.stub)
  implementation(libs.grpc.kotlin.stub)
  implementation(libs.protobuf.kotlin)
  implementation(libs.armeria.grpc)
  implementation(libs.armeria.grpc.kotlin)
  implementation(libs.armeria.kotlin)
  implementation(libs.google.auth.library.oauth2.http)
  implementation(libs.grpc.auth)
  implementation(libs.kotlinx.coroutines.core)
  runtimeOnly(libs.logback.classic)

  // The systemTest source set: JUnit 5 (its `@Tag`s drive the smoke/loop tiers) + coroutines-test.
  // Declared on the `test` configuration, which the custom source set's classpath reuses above.
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlinx.coroutines.test)
  // The JUnit Platform launcher isn't auto-provisioned for a custom source set's Test task, only
  // for the conventional `test` task — add it explicitly (version from the BOM).
  testRuntimeOnly(libs.junit.platform.launcher)
}

val grpcJavaId = "grpc"
val grpcKotlinId = "grpckt"

protobuf {
  protoc { artifact = "${libs.protobuf.protoc.get()}" }

  plugins {
    id(grpcJavaId) { artifact = "${libs.protobuf.protocGen.grpc.java.get()}" }
    id(grpcKotlinId) { artifact = "${libs.protobuf.protocGen.grpc.kotlin.get()}:jdk8@jar" }
  }

  generateProtoTasks {
    all().forEach { protoTask ->
      protoTask.plugins {
        id(grpcJavaId)
        id(grpcKotlinId)
      }
      protoTask.builtins { id("kotlin") }
    }
  }
}

// Comma-separated JUnit tags to include (e.g. `-PsystemTestTags=smoke`), so the promotion gate can
// run one tier at a time and report them as separate steps. Unset → run every tier.
val systemTestTags: String? = providers.gradleProperty("systemTestTags").orNull

tasks.register<Test>(systemTestSourceSetName) {
  description =
      "Runs the system-test suite against a deployed staging environment (smoke + loop tiers). " +
          "Needs API_URL and staging credentials (WIF/ADC); skipped when unconfigured. " +
          "Filter tiers with -PsystemTestTags=smoke|loop."
  group = "verification"

  testClassesDirs = sourceSets[systemTestSourceSetName].output.classesDirs
  classpath = sourceSets[systemTestSourceSetName].runtimeClasspath

  useJUnitPlatform {
    systemTestTags
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?.let { includeTags(*it.toTypedArray()) }
  }

  // The loop tier drives a real session through the real worker; give it comfortable headroom.
  timeout = Duration.ofMinutes(30)

  // A staging gate failure must be diagnosable from CI logs alone (the CLAUDE.md "never spend a CI
  // round-trip on a bare assertion" lesson) — full exceptions and the tests' own stdout.
  testLogging {
    events("failed", "passed", "skipped")
    exceptionFormat = TestExceptionFormat.FULL
    showStandardStreams = true
  }
}

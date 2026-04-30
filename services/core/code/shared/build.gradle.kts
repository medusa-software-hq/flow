import com.google.protobuf.gradle.id

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.sqldelight)
  `java-library`
}

// Proto sources live at the repo root
sourceSets { main { proto { srcDir("../../../../proto") } } }

dependencies {
  api(platform(libs.armeria.bom))
  api(platform(libs.grpc.bom))
  api(project(":opencode-client"))

  api(libs.armeria.grpc)
  api(libs.armeria.grpc.kotlin)
  api(libs.armeria.kotlin)
  api(libs.grpc.kotlin.stub)
  api(libs.grpc.protobuf)
  api(libs.grpc.stub)
  api(libs.kotlinx.coroutines.core)
  api(libs.protobuf.kotlin)

  implementation(libs.sqldelight.sqlite.driver)
  implementation(libs.sqldelight.coroutines)

  runtimeOnly(libs.logback.classic)

  testImplementation(libs.armeria.grpc)
  testImplementation(libs.kotlin.test)
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

sqldelight {
  databases {
    create("FlowDatabase") {
      packageName.set("software.medusa.flow.db")
      schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
    }
  }
}

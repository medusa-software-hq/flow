plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.sqldelight)

  `java-library`
}

// Proto sources live at the repo root
sourceSets { main { proto { srcDir("../../../../proto") } } }

dependencies {
  api(platform(libs.grpc.bom))

  api(libs.grpc.protobuf)
  api(libs.protobuf.kotlin)
  api(libs.logback.classic)

  implementation(libs.sqldelight.sqlite.driver)
  implementation(libs.sqldelight.coroutines)

  api(project(":git"))

  testImplementation(libs.kotlin.test)
}

protobuf { protoc { artifact = "${libs.protobuf.protoc.get()}" } }

sqldelight {
  databases {
    create("FlowDatabase") {
      packageName.set("software.medusa.flow.db")
      schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
    }
  }
}

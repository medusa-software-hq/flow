plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.sqldelight)

  `java-library`
}

dependencies {
  implementation(libs.sqldelight.sqlite.driver)
  implementation(libs.sqldelight.coroutines)

  testImplementation(libs.kotlin.test)
}

sqldelight {
  databases {
    create("FlowDatabase") {
      packageName.set("software.medusa.flow.db")
      schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
    }
  }
}

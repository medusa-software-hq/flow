package software.medusa.flow.server

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import software.medusa.flow.db.FlowDatabase

private const val maxPoolSize = 5

/**
 * Builds the shared [FlowDatabase] backed by the database at [jdbcUrl] (a full JDBC URL including
 * credentials and `sslmode=require`), running pending Flyway migrations before returning.
 *
 * Flyway owns the runtime schema; SQLDelight only provides type-safe queries, so we do not call
 * [FlowDatabase.Schema] create/migrate here. A single instance is shared by every Postgres-backed
 * store ([PostgresCounterStore], [PostgresSessionStore]) so they use one connection pool.
 */
fun buildFlowDatabase(
    jdbcUrl: String,
): FlowDatabase {
  val dataSource: DataSource =
      HikariDataSource(
          HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            // Register the driver explicitly instead of relying on DriverManager's ServiceLoader
            // auto-registration, which is unreliable in the packaged Cloud Run image (it fails with
            // "No suitable driver" even though pgjdbc is on the classpath).
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = maxPoolSize
          },
      )

  Flyway.configure().dataSource(dataSource).load().migrate()

  return FlowDatabase(dataSource.asJdbcDriver())
}

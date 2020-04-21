package com.socrata.geocodingsecondary

import java.io.PrintWriter
import javax.sql.DataSource

import org.postgresql.ds.PGSimpleDataSource
import com.mchange.v2.c3p0.{DataSources, PooledDataSource}
import com.rojoma.simplearm.v2._

import com.socrata.geocodingsecondary.config.PostgresqlConfig

object PostgresqlFromConfig {
  def apply(config: PostgresqlConfig): Managed[DataSource] = {
    val ds = unmanagedWithResource(config)
    managed(ds.dataSource)(ds.resource)
  }

  // sigh.  This could be so much simpler if c3p0's PooledDataSource
  // were to simply implement (Auto)Closeable.  Then we could just
  // instantiate `new PGSimpleDataSource with AutoCloseable {}` in
  // the non-c3p0 branch and their LUB would be `DataSource with
  // AutoCloseable` and we wouldn't need this forwarding machinery.
  def unmanaged(config: PostgresqlConfig): DataSource with AutoCloseable =
    new DataSource with AutoCloseable {
      val dsd = unmanagedWithResource(config)
      val resource = dsd.resource
      val dataSource = dsd.dataSource

      override def close(): Unit = resource.close(dataSource)

      override def getLogWriter = dataSource.getLogWriter
      override def getLoginTimeout = dataSource.getLoginTimeout
      override def getParentLogger = dataSource.getParentLogger
      override def setLogWriter(w: PrintWriter) = dataSource.setLogWriter(w)
      override def setLoginTimeout(t: Int) = dataSource.setLoginTimeout(t)

      override def getConnection(user: String, pass: String) = dataSource.getConnection(user, pass)
      override def getConnection() = dataSource.getConnection()

      override def isWrapperFor(c: Class[_]) = dataSource.isWrapperFor(c)
      override def unwrap[T](c: Class[T]) = dataSource.unwrap(c)
    }

  private trait DataSourceData {
    type T <: DataSource
    def dataSource: T
    def resource: Resource[T]
  }

  private def unmanagedWithResource(config: PostgresqlConfig): DataSourceData = {
    config.poolOptions match {
      case None =>
        new DataSourceData {
          type T = PGSimpleDataSource
          def dataSource = unpooledDataSource(config)
          def resource = new Resource[PGSimpleDataSource] {
            override def close(res: PGSimpleDataSource) {
              // PGSimpleDataSource isn't closeable
            }
          }
        }
      case Some(poolOptions) =>
        new DataSourceData {
          type T = PooledDataSource
          // would be nice if pooledDatasource would return a PooledDataSource
          // instead of merely documenting that it can be cast to one...
          def dataSource = DataSources.pooledDataSource(unpooledDataSource(config), null, poolOptions).asInstanceOf[PooledDataSource]
          def resource = new Resource[PooledDataSource] {
            def close(res: PooledDataSource) {
              res.close()
            }
          }
        }
    }
  }

  private def unpooledDataSource(config: PostgresqlConfig): PGSimpleDataSource = {
    val ds = new PGSimpleDataSource()
    ds.setServerNames(Array(config.host))
    ds.setPortNumbers(Array(config.port))
    ds.setDatabaseName(config.database)
    ds.setUser(config.username)
    ds.setPassword(config.password)
    ds.setApplicationName(config.applicationName)
    ds
  }
}

/*
 * Copyright 2014 Edulify.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.edulify.play.hikaricp

import java.sql.{Driver, DriverManager}
import javax.sql.DataSource

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.jdbcdslog.LogSqlDataSource
import play.api.db.DBApi
import play.api.libs.JNDI
import play.api.{Configuration, Logger}

import scala.util.{Success, Failure, Try}

class HikariCPDBApi(configuration: Configuration, classloader: ClassLoader) extends DBApi {

  lazy val dataSourceConfigs = configuration.subKeys.map {
    dataSourceName => {
      val dataSourceConfig = configuration.getConfig(dataSourceName)
      if (dataSourceConfig.isEmpty) {
        configuration.reportError(
          path = s"db.$dataSourceName",
          message = s"Missing data source configuration for db.$dataSourceName"
        )
      }
      dataSourceName -> dataSourceConfig.getOrElse(Configuration.empty)
    }
  }

  val hikariDataSources = dataSourceConfigs.map {
    case (dataSourceName, dataSourceConfig) =>
      val hikariConfig = HikariCPConfig.toHikariConfig(dataSourceName, dataSourceConfig)
      Logger.info(s"Creating Pool for datasource '$dataSourceName'")
      dataSourceName -> (new HikariDataSource(hikariConfig), hikariConfig, dataSourceConfig)
  }.toMap

  val datasources: List[(DataSource, String)] = hikariDataSources.map {
    case (dataSourceName, dataSourceTuple) =>
      val hikariDataSource = dataSourceTuple._1
      val hikariConfig = dataSourceTuple._2
      val dataSourceConfig = dataSourceTuple._3

      Try {
        registerDriver(dataSourceConfig)

        if (dataSourceConfig.getBoolean("logSql").getOrElse(false)) {
          val dataSourceWithLogging = new LogSqlDataSource()
          dataSourceWithLogging.setTargetDSDirect(hikariDataSource)
          bindToJNDI(dataSourceConfig, hikariConfig, dataSourceWithLogging)
          dataSourceWithLogging -> dataSourceName
        } else {
          bindToJNDI(dataSourceConfig, hikariConfig, hikariDataSource)
          hikariDataSource -> dataSourceName
        }
      } match {
        case Success(result) => result
        case Failure(ex) => throw dataSourceConfig.reportError(dataSourceName, ex.getMessage, Some(ex))
      }
  }.toList

  def softEvictConnections(dataSourceName: String) = {
    hikariDataSources.get(dataSourceName).foreach {
      case (hikariDataSource: HikariDataSource, _, _) =>
        Logger.info(s"Soft evicting connections for data source '$dataSourceName'")
        hikariDataSource.getHikariPoolMXBean.softEvictConnections()
    }
  }

  def shutdownPool(ds: DataSource) = {
    Logger.info("Shutting down connection pool.")
    ds match {
      case ds: HikariDataSource => ds.close()
      case ds: LogSqlDataSource => ds.shutdown()
      case _ => Logger.debug("DataSource type was not recognized by HikariCP Plugin")
    }
  }

  def getDataSource(name: String): DataSource = {
    datasources
      .find { case (_, dsName) => dsName == name }
      .map  { case (ds, _) => ds }
      .getOrElse(sys.error(s" - could not find data source for name $name"))
  }

  private def registerDriver(config: Configuration) = {
    config.getString("driverClassName").foreach { driverClassName =>
      Try {
        Logger.info("Registering driver " + driverClassName)
        DriverManager.registerDriver(new play.utils.ProxyDriver(Class.forName(driverClassName, true, classloader)
          .newInstance
          .asInstanceOf[Driver]))
      } match {
        case Success(r) => Logger.info("Driver was successfully registered")
        case Failure(e) => throw config.reportError("driverClassName", s"Driver not found: [$driverClassName]", Some(e))
      }
    }
  }

  private def bindToJNDI(config: Configuration, hikariConfig: HikariConfig, dataSource: DataSource): Unit = {
    config.getString("jndiName").foreach { name =>
      JNDI.initialContext.rebind(name, dataSource)
      Logger.info(s"datasource [${hikariConfig.getJdbcUrl}] bound to JNDI as $name")
    }
  }
}

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

import java.sql.{SQLException, DriverManager}
import java.util.Properties

import com.typesafe.config.ConfigFactory
import org.jdbcdslog.LogSqlDataSource
import org.specs2.execute.AsResult
import org.specs2.mutable.Specification
import org.specs2.specification.{Scope, AroundExample}
import play.api.{PlayException, Configuration}
import play.api.libs.JNDI
import scala.collection.JavaConversions._
import scala.util.control.NonFatal

class HikariCPDBApiSpec extends Specification with AroundExample {

  "When starting HikariCP DB API" should {
    "create data sources" in new DataSourceConfigs {
      val api = new HikariCPDBApi(config, classLoader)
      val ds = api.getDataSource("default")
      ds.getConnection.getMetaData.getURL == "jdbc:h2:mem:test"
    }
    "create data source with logSql enabled" in new DataSourceConfigs {
      val api = new HikariCPDBApi(configWithLogSql, classLoader)
      val ds = api.getDataSource("default")
      ds.isInstanceOf[LogSqlDataSource] must beTrue
    }
    "bind data source to jndi" in new DataSourceConfigs {
      val api = new HikariCPDBApi(configWithLogSql, classLoader)
      val ds = api.getDataSource("default")
      JNDI.initialContext.lookup("TestContext") must not(beNull)
    }
    "logSql for data sources bounded to jndi" in new DataSourceConfigs {
      val api = new HikariCPDBApi(configWithLogSql, classLoader)
      val ds = api.getDataSource("default")
      val boundedDS = JNDI.initialContext.lookup("TestContext")
      boundedDS.isInstanceOf[LogSqlDataSource] must beTrue
    }
    "register driver configured in `driverClassName`" in new DataSourceConfigs {
      val api = new HikariCPDBApi(configWithLogSql, classLoader)
      val ds = api.getDataSource("default")
      DriverManager.getDrivers.exists( driver => driver.getClass.getName == "org.h2.Driver") must beTrue
    }
    "create more than one datasource" in new DataSourceConfigs {
      val api = new HikariCPDBApi(multipleDataSources, classLoader)
      api.getDataSource("default")  must not(beNull)
      api.getDataSource("default2") must not(beNull)
    }
    "report misconfiguration error when" in {
      "dataSourceClassName and jdbcUrl are not present" in new DataSourceConfigs {
        val properties = new Properties()
        properties.setProperty("default.username", "sa")
        properties.setProperty("default.password", "")

        val misConfig = new Configuration(ConfigFactory.parseProperties(properties))
        new HikariCPDBApi(misConfig, classLoader) must throwA[IllegalArgumentException]
      }
      "db configuration has no dataSources configured" in new DataSourceConfigs {
        val properties = new Properties()
        properties.setProperty("default", "")
        val misConfig = new Configuration(ConfigFactory.parseProperties(properties))
        new HikariCPDBApi(misConfig, classLoader) must throwA[PlayException]
      }
    }
  }

  "When shutting down pool" should {
    "shutdown data source" in new DataSourceConfigs {
      val api = new HikariCPDBApi(config, classLoader)
      val ds = api.getDataSource("default")
      api.shutdownPool(ds)
      ds.getConnection must throwA[SQLException]
    }
    "shutdown data source with logSql enabled" in new DataSourceConfigs {
      val api = new HikariCPDBApi(configWithLogSql, classLoader)
      val ds = api.getDataSource("default")
      api.shutdownPool(ds)
      ds.getConnection must throwA[SQLException]
    }
  }

  def around[T : AsResult](t: =>T) = {
    Class.forName("org.h2.Driver")
    val conn = DriverManager.getConnection("jdbc:h2:mem:test", "sa",  "")
    try {
      val result = AsResult(t)
      result
    } catch {
      case NonFatal(e) => failure(e.getMessage)
    } finally {
      conn.close()
    }
  }
}

trait DataSourceConfigs extends Scope {
  def config = new Configuration(ConfigFactory.parseProperties(Props().properties))
  def configWithLogSql = {
    val props = new Props().properties
    props.setProperty("default.logSql", "true")
    new Configuration(ConfigFactory.parseProperties(props))
  }
  def multipleDataSources = new Configuration(ConfigFactory.parseProperties(Props().multipleDatabases))
  def classLoader = this.getClass.getClassLoader
}

case class Props() {
  def properties = {
    val props = new Properties()
    props.setProperty("default.jdbcUrl", "jdbc:h2:mem:test")
    props.setProperty("default.username", "sa")
    props.setProperty("default.password", "")
    props.setProperty("default.driverClassName", "org.h2.Driver")
    props.setProperty("default.jndiName", "TestContext")
    props
  }
  def multipleDatabases = {
    val props = new Properties()
    props.setProperty("default.jdbcUrl", "jdbc:h2:mem:test")
    props.setProperty("default.username", "sa")
    props.setProperty("default.password", "")
    props.setProperty("default.driverClassName", "org.h2.Driver")
    props.setProperty("default.jndiName", "TestContext")

    // default2
    props.setProperty("default2.jdbcUrl", "jdbc:h2:mem:test")
    props.setProperty("default2.username", "sa")
    props.setProperty("default2.password", "")
    props.setProperty("default2.driverClassName", "org.h2.Driver")
    props.setProperty("default2.jndiName", "TestContext")
    props
  }
}
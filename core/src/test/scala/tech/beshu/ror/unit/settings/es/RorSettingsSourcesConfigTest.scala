/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.unit.settings.es

import better.files.File
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import squants.information.Megabytes
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorSettingsFile, RorSettingsIndex}
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader.LoadingError
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader.LoadingError.MalformedSettings
import tech.beshu.ror.settings.es.RorSettingsSourcesConfig
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.{TestsEnvVarsProvider, TestsPropertiesProvider}
import tech.beshu.ror.utils.TestsUtils.withEsEnv

class RorSettingsSourcesConfigTest extends AnyWordSpec with Inside {

  "ROR settings sources config" should {
    "use default values" when {
      "no readonlyrest settings are present in elasticsearch config" in {
        val (configDir, result) = load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |""".stripMargin
        )

        result should be(Right(RorSettingsSourcesConfig(
          settingsIndex = RorSettingsIndex.default,
          settingsFile = RorSettingsFile(configDir / "readonlyrest.yml"),
          settingsMaxSize = Megabytes(3)
        )))
      }
    }
    "load all settings from elasticsearch config" in {
      val (_, result) = load(
        """
          |readonlyrest:
          |  settings:
          |    index_name: .my-ror-index
          |    file_path: /custom/path/readonlyrest.yml
          |    max_size: 10 MB
          |""".stripMargin
      )

      result should be(Right(RorSettingsSourcesConfig(
        settingsIndex = RorSettingsIndex(IndexName.Full(nes(".my-ror-index"))),
        settingsFile = RorSettingsFile(File("/custom/path/readonlyrest.yml")),
        settingsMaxSize = Megabytes(10)
      )))
    }
    "load all settings from JVM properties" in {
      val (_, result) = load(
        """
          |node.name: n1_it
          |""".stripMargin,
        properties = Map(
          "readonlyrest.settings.index_name" -> ".my-ror-index",
          "readonlyrest.settings.file_path"  -> "/custom/path/readonlyrest.yml",
          "readonlyrest.settings.max_size"   -> "10 MB"
        )
      )

      result should be(Right(RorSettingsSourcesConfig(
        settingsIndex = RorSettingsIndex(IndexName.Full(nes(".my-ror-index"))),
        settingsFile = RorSettingsFile(File("/custom/path/readonlyrest.yml")),
        settingsMaxSize = Megabytes(10)
      )))
    }
    "load index name from legacy YAML key" when {
      "readonlyrest.settings_index is defined" in {
        val (configDir, result) = load(
          """
            |readonlyrest:
            |  settings_index: .my-legacy-ror-index
            |""".stripMargin
        )

        result should be(Right(RorSettingsSourcesConfig(
          settingsIndex = RorSettingsIndex(IndexName.Full(nes(".my-legacy-ror-index"))),
          settingsFile = RorSettingsFile(configDir / "readonlyrest.yml"),
          settingsMaxSize = Megabytes(3)
        )))
      }
    }
    "load index name from JVM property" when {
      "readonlyrest.settings.index_name is set" in {
        val (configDir, result) = load(
          """
            |node.name: n1_it
            |""".stripMargin,
          properties = Map("readonlyrest.settings.index_name" -> ".my-ror-index")
        )

        result should be(Right(RorSettingsSourcesConfig(
          settingsIndex = RorSettingsIndex(IndexName.Full(nes(".my-ror-index"))),
          settingsFile = RorSettingsFile(configDir / "readonlyrest.yml"),
          settingsMaxSize = Megabytes(3)
        )))
      }
    }
    "load file path from JVM property" when {
      "readonlyrest.settings.file_path is set" in {
        val (_, result) = load(
          """
            |node.name: n1_it
            |""".stripMargin,
          properties = Map("readonlyrest.settings.file_path" -> "/my/path/readonlyrest.yml")
        )

        result should be(Right(RorSettingsSourcesConfig(
          settingsIndex = RorSettingsIndex.default,
          settingsFile = RorSettingsFile(File("/my/path/readonlyrest.yml")),
          settingsMaxSize = Megabytes(3)
        )))
      }
    }
    "load max size from JVM property" when {
      "readonlyrest.settings.max_size is set" in {
        val (configDir, result) = load(
          """
            |node.name: n1_it
            |""".stripMargin,
          properties = Map("readonlyrest.settings.max_size" -> "10 MB")
        )

        result should be(Right(RorSettingsSourcesConfig(
          settingsIndex = RorSettingsIndex.default,
          settingsFile = RorSettingsFile(configDir / "readonlyrest.yml"),
          settingsMaxSize = Megabytes(10)
        )))
      }
    }
    "load file path from legacy JVM property" when {
      "com.readonlyrest.settings.file.path is set" in {
        val properties = Map("com.readonlyrest.settings.file.path" -> "/legacy/path/readonlyrest.yml")
        val (_, result) = load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |""".stripMargin,
          properties
        )

        result should be(Right(RorSettingsSourcesConfig(
          settingsIndex = RorSettingsIndex.default,
          settingsFile = RorSettingsFile(File("/legacy/path/readonlyrest.yml")),
          settingsMaxSize = Megabytes(3)
        )))
      }
    }
    "load max size from legacy JVM property" when {
      "com.readonlyrest.settings.maxSize is set" in {
        val properties = Map("com.readonlyrest.settings.maxSize" -> "5 MB")
        val (configDir, result) = load(
          """
            |node.name: n1_it
            |cluster.initial_master_nodes: n1_it
            |""".stripMargin,
          properties
        )

        result should be(Right(RorSettingsSourcesConfig(
          settingsIndex = RorSettingsIndex.default,
          settingsFile = RorSettingsFile(configDir / "readonlyrest.yml"),
          settingsMaxSize = Megabytes(5)
        )))
      }
    }
    "load all settings from OS environment variables" in {
      val (_, result) = load(
        """
          |node.name: n1_it
          |""".stripMargin,
        envVars = Map(
          "ES_SETTING_READONLYREST_SETTINGS_INDEX__NAME" -> ".my-ror-index",
          "ES_SETTING_READONLYREST_SETTINGS_FILE__PATH"  -> "/custom/path/readonlyrest.yml",
          "ES_SETTING_READONLYREST_SETTINGS_MAX__SIZE"   -> "10 MB"
        )
      )

      result should be(Right(RorSettingsSourcesConfig(
        settingsIndex = RorSettingsIndex(IndexName.Full(nes(".my-ror-index"))),
        settingsFile = RorSettingsFile(File("/custom/path/readonlyrest.yml")),
        settingsMaxSize = Megabytes(10)
      )))
    }
    "fail to load" when {
      "max_size has an invalid value" in {
        inside(load(
          """
            |readonlyrest:
            |  settings:
            |    max_size: not-a-size
            |""".stripMargin
        )._2) {
          case Left(MalformedSettings(_, message)) =>
            message should include(
              "Invalid value at '.readonlyrest.settings.max_size': " +
                "Cannot parse 'not-a-size' as a data size. Expected format like '1 MB', '512 KB'"
            )
        }
      }
    }
  }

  private def load(yaml: String,
                   properties: Map[String, String] = Map.empty,
                   envVars: Map[String, String] = Map.empty): (File, Either[LoadingError, RorSettingsSourcesConfig]) = {
    implicit val systemContext: SystemContext = new SystemContext(
      propertiesProvider = TestsPropertiesProvider.usingMap(properties),
      envVarsProvider    = TestsEnvVarsProvider.usingMap(envVars)
    )
    withEsEnv(yaml) { (esEnv, configDir) =>
      val result = RorSettingsSourcesConfig.from(esEnv).runSyncUnsafe()
      (configDir, result)
    }
  }
}

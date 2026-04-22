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
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import squants.information.Megabytes
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorSettingsFile, RorSettingsIndex}
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader.LoadingError.MalformedSettings
import tech.beshu.ror.settings.es.RorSettingsSourcesConfig
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils.{defaultEsVersionForTests, defaultTestEsNodeSettings, getResourcePath}

class RorSettingsSourcesConfigTest extends AnyWordSpec {

  "ROR settings sources config" should {
    "use default values" when {
      "no readonlyrest settings are present in elasticsearch config" in {
        val esConfigFolderPath = "/boot_tests/settings_sources_config/no_settings"
        val result = load(esConfigFolderPath)

        result should be(Right(RorSettingsSourcesConfig(
          settingsIndex = RorSettingsIndex.default,
          settingsFile = RorSettingsFile(File(getResourcePath(esConfigFolderPath)) / "readonlyrest.yml"),
          settingsMaxSize = Megabytes(3)
        )))
      }
    }
    "load all settings from elasticsearch config" in {
      val result = load("/boot_tests/settings_sources_config/all_settings_defined")

      result should be(Right(RorSettingsSourcesConfig(
        settingsIndex = RorSettingsIndex(IndexName.Full(nes(".my-ror-index"))),
        settingsFile = RorSettingsFile(File("/custom/path/readonlyrest.yml")),
        settingsMaxSize = Megabytes(10)
      )))
    }
    "load index name from legacy YAML key" when {
      "readonlyrest.settings_index is defined" in {
        val esConfigFolderPath = "/boot_tests/settings_sources_config/legacy_index_name"
        val result = load(esConfigFolderPath)

        result should be(Right(RorSettingsSourcesConfig(
          settingsIndex = RorSettingsIndex(IndexName.Full(nes(".my-legacy-ror-index"))),
          settingsFile = RorSettingsFile(File(getResourcePath(esConfigFolderPath)) / "readonlyrest.yml"),
          settingsMaxSize = Megabytes(3)
        )))
      }
    }
    "load file path from legacy JVM property" when {
      "com.readonlyrest.settings.file.path is set" in {
        val esConfigFolderPath = "/boot_tests/settings_sources_config/no_settings"
        val properties = Map("com.readonlyrest.settings.file.path" -> "/legacy/path/readonlyrest.yml")
        val result = load(esConfigFolderPath, properties)

        result should be(Right(RorSettingsSourcesConfig(
          settingsIndex = RorSettingsIndex.default,
          settingsFile = RorSettingsFile(File("/legacy/path/readonlyrest.yml")),
          settingsMaxSize = Megabytes(3)
        )))
      }
    }
    "load max size from legacy JVM property" when {
      "com.readonlyrest.settings.maxSize is set" in {
        val esConfigFolderPath = "/boot_tests/settings_sources_config/no_settings"
        val properties = Map("com.readonlyrest.settings.maxSize" -> "5 MB")
        val result = load(esConfigFolderPath, properties)

        result should be(Right(RorSettingsSourcesConfig(
          settingsIndex = RorSettingsIndex.default,
          settingsFile = RorSettingsFile(File(getResourcePath(esConfigFolderPath)) / "readonlyrest.yml"),
          settingsMaxSize = Megabytes(5)
        )))
      }
    }
    "fail to load" when {
      "max_size has an invalid value" in {
        val esConfigFolderPath = "/boot_tests/settings_sources_config/malformed_max_size"
        val expectedFilePath = getResourcePath(s"$esConfigFolderPath/elasticsearch.yml")

        load(esConfigFolderPath) should be(Left(MalformedSettings(
          expectedFilePath,
          s"Cannot load ROR settings source settings from file ${expectedFilePath.toString}. " +
            s"Cause: Invalid value at '.readonlyrest.settings.max_size': " +
            s"Cannot parse 'not-a-size' as a data size. Expected format like '1 MB', '512 KB'"
        )))
      }
    }
  }

  private def load(resourceEsConfigFolderPath: String,
                   properties: Map[String, String] = Map.empty) = {
    implicit val systemContext: SystemContext = new SystemContext(
      propertiesProvider = TestsPropertiesProvider.usingMap(properties)
    )
    RorSettingsSourcesConfig
      .from(esEnvFrom(resourceEsConfigFolderPath))
      .runSyncUnsafe()
  }

  private def esEnvFrom(resourceEsConfigFolderPath: String) = EsEnv(
    getResourcePath(resourceEsConfigFolderPath),
    getResourcePath(resourceEsConfigFolderPath),
    defaultEsVersionForTests,
    defaultTestEsNodeSettings
  )
}

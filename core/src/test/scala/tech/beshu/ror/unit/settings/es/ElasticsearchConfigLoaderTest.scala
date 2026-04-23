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
import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.SystemContext
import tech.beshu.ror.providers.{EnvVarsProvider, PropertiesProvider}
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader.LoadingError
import tech.beshu.ror.utils.FromString
import tech.beshu.ror.utils.{TestsEnvVarsProvider, TestsPropertiesProvider}
import tech.beshu.ror.utils.yaml.YamlLeafOrPropertyDecoder

class ElasticsearchConfigLoaderTest extends AnyWordSpec with Inside {

  private given PropertiesProvider = TestsPropertiesProvider.default
  private given EnvVarsProvider = TestsEnvVarsProvider.default
  private given SystemContext = SystemContext.default

  "ElasticsearchConfigLoader" when {

    "reading a required value from nested YAML" should {
      "decode the value at the given path" in {
        val yaml =
          """
            |readonlyrest:
            |  settings:
            |    index_name: .my-ror-index
            |""".stripMargin

        given YamlLeafOrPropertyDecoder[String] = requiredAt("readonlyrest", "settings", "index_name")

        loadWith[String](yaml) should be(Right(".my-ror-index"))
      }
      "fail when the required path is absent from the config" in {
        val yaml = "node.name: my-node"

        given YamlLeafOrPropertyDecoder[String] = requiredAt("readonlyrest", "settings", "index_name")

        inside(loadWith[String](yaml)) { case Left(_) => }
      }
    }

    "reading an optional value from nested YAML" should {
      "return the value when the path is present" in {
        val yaml =
          """
            |readonlyrest:
            |  settings:
            |    file_path: /etc/ror/readonlyrest.yml
            |""".stripMargin

        given YamlLeafOrPropertyDecoder[Option[String]] = optionalAt("readonlyrest", "settings", "file_path")

        loadWith[Option[String]](yaml) should be(Right(Some("/etc/ror/readonlyrest.yml")))
      }
      "return None when the path is absent" in {
        val yaml = "node.name: my-node"

        given YamlLeafOrPropertyDecoder[Option[String]] = optionalAt("readonlyrest", "settings", "file_path")

        loadWith[Option[String]](yaml) should be(Right(None))
      }
      "coerce a boolean scalar to string" in {
        val yaml =
          """
            |readonlyrest:
            |  ssl:
            |    enable: true
            |""".stripMargin

        given YamlLeafOrPropertyDecoder[Option[String]] = optionalAt("readonlyrest", "ssl", "enable")

        loadWith[Option[String]](yaml) should be(Right(Some("true")))
      }
    }

    "falling back to JVM system properties" should {
      "use the property value when the YAML path is absent" in {
        val yaml = "node.name: my-node"

        given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
          "readonlyrest.settings.index_name" -> ".ror-from-property"
        ))
        given YamlLeafOrPropertyDecoder[Option[String]] = optionalAt("readonlyrest", "settings", "index_name")

        loadWith[Option[String]](yaml) should be(Right(Some(".ror-from-property")))
      }
      "prefer the YAML value over the JVM property when both are set" in {
        val yaml =
          """
            |readonlyrest:
            |  settings:
            |    index_name: .ror-from-yaml
            |""".stripMargin

        given PropertiesProvider = TestsPropertiesProvider.usingMap(Map(
          "readonlyrest.settings.index_name" -> ".ror-from-property"
        ))
        given YamlLeafOrPropertyDecoder[Option[String]] = optionalAt("readonlyrest", "settings", "index_name")

        loadWith[Option[String]](yaml) should be(Right(Some(".ror-from-yaml")))
      }
    }

    "resolving environment variables in YAML values" should {
      "substitute a known environment variable" in {
        val yaml =
          """
            |readonlyrest:
            |  settings:
            |    index_name: "${ROR_INDEX_NAME}"
            |""".stripMargin

        given YamlLeafOrPropertyDecoder[Option[String]] = optionalAt("readonlyrest", "settings", "index_name")

        loadWith[Option[String]](yaml, envVars = Map("ROR_INDEX_NAME" -> ".ror-env-index")) should be(Right(Some(".ror-env-index")))
      }
      "fail when the environment variable is not defined" in {
        val yaml =
          """
            |readonlyrest:
            |  settings:
            |    index_name: "${UNDEFINED_VAR}"
            |""".stripMargin

        given YamlLeafOrPropertyDecoder[Option[String]] = optionalAt("readonlyrest", "settings", "index_name")

        inside(loadWith[Option[String]](yaml)) {
          case Left(error: LoadingError.MalformedSettings) =>
            error.message should include("UNDEFINED_VAR")
        }
      }
    }

    "the file does not exist" should {
      "return FileNotFound error" in {
        given YamlLeafOrPropertyDecoder[Option[String]] = optionalAt("readonlyrest", "settings", "index_name")

        val result = new ElasticsearchConfigLoader(File("non-existing-elasticsearch.yml"))
          .loadSettings[Option[String]]("test settings")
          .runSyncUnsafe()

        inside(result) {
          case Left(_: LoadingError.FileNotFound) =>
        }
      }
    }

    "the YAML is malformed" should {
      "return MalformedSettings error" in {
        val yaml = "readonlyrest: { unclosed"

        given YamlLeafOrPropertyDecoder[Option[String]] = optionalAt("readonlyrest", "settings", "index_name")

        inside(loadWith[Option[String]](yaml)) {
          case Left(_: LoadingError.MalformedSettings) =>
        }
      }
    }
  }

  private def optionalAt(segments: String*)(using PropertiesProvider): YamlLeafOrPropertyDecoder[Option[String]] =
    YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
      path = NonEmptyList.fromListUnsafe(segments.toList.map(NonEmptyString.unsafeFrom)),
      decoder = FromString.string
    )

  private def requiredAt(segments: String*)(using PropertiesProvider): YamlLeafOrPropertyDecoder[String] =
    YamlLeafOrPropertyDecoder.createRequiredValueDecoder(
      path = NonEmptyList.fromListUnsafe(segments.toList.map(NonEmptyString.unsafeFrom)),
      decoder = FromString.string
    )

  private def loadWith[A: YamlLeafOrPropertyDecoder](content: String, envVars: Map[String, String] = Map.empty): Either[LoadingError, A] = {
    given SystemContext = new SystemContext(envVarsProvider = name => envVars.get(name.value.value))
    File
      .temporaryFile()
      .map(_.write(content))
      .map(file => new ElasticsearchConfigLoader(file).loadSettings[A]("test settings").runSyncUnsafe())
      .get()
  }
}

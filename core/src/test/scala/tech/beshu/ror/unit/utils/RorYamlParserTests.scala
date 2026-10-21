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
package tech.beshu.ror.unit.utils

import better.files.File
import io.circe.Json
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import squants.information.{Bytes, Kilobytes, Megabytes}
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.ror.RawRorSettingsYamlParser
import tech.beshu.ror.settings.ror.source.FileSettingsSource
import tech.beshu.ror.settings.ror.source.ReadOnlySettingsSource.SettingsLoadingError
import tech.beshu.ror.utils.yaml.YamlParser

import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

class RorYamlParserTests extends AnyWordSpec with Inside with Matchers {

  private val settingsYamlParser = new RawRorSettingsYamlParser(Megabytes(1))

  "Yaml parser" should {
    "return parsing failure error" when {
      "ROR settings has duplicated 'readonlyrest' section" in {
        val result = settingsYamlParser.fromString("""
                                                     |readonlyrest:
                                                     |
                                                     |    access_control_rules:
                                                     |
                                                     |    - name: "CONTAINER ADMIN1"
                                                     |      verbosity: "error"
                                                     |      type: "allow"
                                                     |      auth_key: "admin:container"
                                                     |
                                                     |readonlyrest:
                                                     |
                                                     |    access_control_rules:
                                                     |
                                                     |    - name: "CONTAINER ADMIN2"
                                                     |      verbosity: "error"
                                                     |      type: "allow"
                                                     |      auth_key: "admin:container"
                                                     |
                                                     |""".stripMargin)

        inside(result) { case Left(error) =>
          error.show should be("Settings content is malformed. Details: Duplicated key: 'readonlyrest'")
        }
      }
      "ROR settings has duplicated 'access_control_rules' section" in {
        val result = settingsYamlParser.fromString("""
                                                     |readonlyrest:
                                                     |
                                                     |    access_control_rules:
                                                     |
                                                     |    - name: "CONTAINER ADMIN1"
                                                     |      verbosity: "error"
                                                     |      type: "allow"
                                                     |      auth_key: "admin:container"
                                                     |
                                                     |    access_control_rules:
                                                     |
                                                     |    - name: "CONTAINER ADMIN2"
                                                     |      verbosity: "error"
                                                     |      type: "allow"
                                                     |      auth_key: "admin:container"
                                                     |
                                                     |""".stripMargin)

        inside(result) { case Left(error) =>
          error.show should be("Settings content is malformed. Details: Duplicated key: 'access_control_rules'")
        }
      }
      "ROR settings has duplicated definition" in {
        val result = settingsYamlParser.fromString("""
                                                     |readonlyrest:
                                                     |
                                                     |    access_control_rules:
                                                     |
                                                     |    - name: "CONTAINER ADMIN1"
                                                     |      verbosity: "error"
                                                     |      type: "allow"
                                                     |      auth_key: "admin:container"
                                                     |
                                                     |    proxy_auth_configs:
                                                     |
                                                     |    - name: "proxy1"
                                                     |      user_id_header: "X-Auth-Token1"
                                                     |
                                                     |    proxy_auth_configs:
                                                     |
                                                     |    - name: "proxy2"
                                                     |      user_id_header: "X-Auth-Token2"
                                                     |
                                                     |""".stripMargin)

        inside(result) { case Left(error) =>
          error.show should be("Settings content is malformed. Details: Duplicated key: 'proxy_auth_configs'")
        }
      }
      "block has duplicated rule" in {
        val result = settingsYamlParser.fromString("""
                                                     |readonlyrest:
                                                     |
                                                     |    access_control_rules:
                                                     |
                                                     |    - name: "CONTAINER ADMIN - updated1"
                                                     |      verbosity: "error"
                                                     |      type: "allow"
                                                     |      auth_key: "admin:container"
                                                     |      auth_key: "admin2:container2"
                                                     |
                                                     |""".stripMargin)

        inside(result) { case Left(error) =>
          error.show should be("Settings content is malformed. Details: Duplicated key: 'auth_key'")
        }
      }
    }
    "return valid ror settings" when {
      "none of the keys is duplicated within its scope" in {
        val rawConfig =
          """
            |readonlyrest:
            |
            |    access_control_rules:
            |
            |    - name: "CONTAINER ADMIN1"
            |      verbosity: "error"
            |      type: "allow"
            |      auth_key: "admin:container"
            |
            |    - name: "CONTAINER ADMIN2"
            |      verbosity: "error"
            |      type: "allow"
            |      auth_key: "admin2:container"
            |
            |    proxy_auth_configs:
            |
            |    - name: "proxy1"
            |      user_id_header: "X-Auth-Token1"
            |
            |    - name: "proxy2"
            |      user_id_header: "X-Auth-Token2"
            |
            |""".stripMargin

        val result = settingsYamlParser.fromString(rawConfig)

        inside(result) { case Right(settings) =>
          settings.rawYaml shouldBe rawConfig
        }
      }
    }
    "read a settings file as UTF-8" when {
      val nonAsciiYaml =
        """
          |readonlyrest:
          |
          |    access_control_rules:
          |
          |    - name: "zażółć gęślą jaźń"
          |      auth_key: "admin:container"
          |""".stripMargin

      "the file is parsed by the ROR settings parser" in {
        withUtf8File(nonAsciiYaml) { file =>
          inside(settingsYamlParser.fromFile(file)) { case Right(settings) =>
            settings.rawYaml shouldBe nonAsciiYaml
          }
        }
      }
      "the file is parsed by the YAML parser" in {
        withUtf8File(nonAsciiYaml) { file =>
          new YamlParser().parse(file) shouldBe new YamlParser().parse(nonAsciiYaml)
        }
      }
      "the file is loaded by the file settings source" in {
        withUtf8File(nonAsciiYaml) { file =>
          loadWithFileSettingsSource(file) shouldBe Right(nonAsciiYaml)
        }
      }
      "the file starts with a UTF-8 byte order mark" in {
        withFile(utf8ByteOrderMark ++ nonAsciiYaml.getBytes(UTF_8)) { file =>
          inside(settingsYamlParser.fromFile(file)) { case Right(settings) =>
            settings.rawYaml shouldBe nonAsciiYaml
          }
          new YamlParser().parse(file) shouldBe new YamlParser().parse(nonAsciiYaml)
          loadWithFileSettingsSource(file) shouldBe Right(nonAsciiYaml)
        }
      }
    }
    "return an error for a settings file that is not valid UTF-8" when {
      val latin1Yaml = "readonlyrest:\n  access_control_rules:\n  - name: \"café\"\n".getBytes(ISO_8859_1)

      "the file is parsed by the ROR settings parser" in {
        withFile(latin1Yaml) { file =>
          inside(settingsYamlParser.fromFile(file)) { case Left(error) =>
            error.show should be(s"Settings content is malformed. Details: ${notUtf8Message(file)}")
          }
        }
      }
      "the file is parsed by the YAML parser" in {
        withFile(latin1Yaml) { file =>
          inside(new YamlParser().parse(file)) { case Left(failure) =>
            failure.message should be(notUtf8Message(file))
          }
        }
      }
      "the file is loaded by the file settings source" in {
        withFile(latin1Yaml) { file =>
          loadWithFileSettingsSource(file) shouldBe Left(SettingsLoadingError.SettingsMalformed(notUtf8Message(file)))
        }
      }
    }
    "yaml parser relies on parsing numbers from yaml" when {
      "is represented as float" in {
        parseYaml("200.0") shouldEqual Json.fromBigDecimal(200)
      }
      "is represented as int" in {
        parseYaml("200") shouldEqual Json.fromBigDecimal(200)
      }
    }
    "yaml parser relies on stringifying parsed json" when {
      "is represented as float" in {
        parseYaml("200.0").noSpaces shouldEqual "200.0"
      }
      "is represented as int" in {
        parseYaml("200").noSpaces shouldEqual "200"
      }
    }
    "yaml parser can be limited with max yaml size" in {
      val yamlContent: String =
        """
          |readonlyrest:
          |
          |    access_control_rules:
          |
          |    - name: "CONTAINER ADMIN1"
          |      verbosity: "error"
          |      type: "allow"
          |      auth_key: "admin:container"
          |""".stripMargin

      val result = new YamlParser(Some(Bytes(10))).parse(yamlContent)
      inside(result) { case Left(error) =>
        error.show should be("ParsingFailure: The incoming YAML document exceeds the limit: 10 code points.")
      }
    }
  }

  private def parseYaml(yamlContent: String): Json =
    new YamlParser(Some(Kilobytes(100))).parse(yamlContent).toTry.get

  private val utf8ByteOrderMark = Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte)

  private def notUtf8Message(file: File) = s"The file ${file.pathAsString} is not valid UTF-8"

  private def loadWithFileSettingsSource(file: File) =
    new FileSettingsSource[String](file).load()(RequestId("test")).runSyncUnsafe()

  private def withUtf8File[A](content: String)(f: File => A): A = withFile(content.getBytes(UTF_8))(f)

  private def withFile[A](content: Array[Byte])(f: File => A): A =
    File
      .temporaryFile()
      .map(_.writeByteArray(content))
      .map(f)
      .get()

}

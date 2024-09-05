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

import io.circe.{Json, ParsingFailure}
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import squants.information.{Bytes, Kilobytes}
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.yaml.RorYamlParser

class RorYamlParserTests extends AnyWordSpec with Inside with Matchers {

  "Yaml parser" should {
    "return parsing failure error" when {
      "readonlyrest config has duplicated 'readonlyrest' section" in {
        val result = rorConfigFrom(
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

        inside(result) {
          case Left(failure) =>
            failure.message should be("Duplicated key: 'readonlyrest'")
        }
      }
      "readonlyrest config has duplicated 'access_control_rules' section" in {
        val result = rorConfigFrom(
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
            |    access_control_rules:
            |
            |    - name: "CONTAINER ADMIN2"
            |      verbosity: "error"
            |      type: "allow"
            |      auth_key: "admin:container"
            |
            |""".stripMargin)

        inside(result) {
          case Left(failure) =>
            failure.message should be("Duplicated key: 'access_control_rules'")
        }
      }
      "readonlyrest config has duplicated definition" in {
        val result = rorConfigFrom(
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

        inside(result) {
          case Left(failure) =>
            failure.message should be("Duplicated key: 'proxy_auth_configs'")
        }
      }
      "block has duplicated rule" in {
        val result = rorConfigFrom(
          """
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

        inside(result) {
          case Left(failure) =>
            failure.message should be("Duplicated key: 'auth_key'")
        }
      }
    }
    "return valid ror config" when {
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

        val result = rorConfigFrom(rawConfig)

        inside(result) {
          case Right(config) => config.raw shouldBe rawConfig
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

      val result = new RorYamlParser(Bytes(10)).parse(yamlContent)
      inside(result) {
        case Left(parsingFailure) =>
          parsingFailure.message should be("The incoming YAML document exceeds the limit: 10 code points.")
      }
    }
  }

  private def parseYaml(yamlContent: String): Json =
    new RorYamlParser(Kilobytes(100)).parse(yamlContent).toTry.get
}

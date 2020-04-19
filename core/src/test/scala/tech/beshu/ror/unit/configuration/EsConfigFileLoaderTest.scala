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
package tech.beshu.ror.unit.configuration

import better.files.File
import cats.implicits._
import io.circe.Decoder
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.configuration.{EsConfigFileLoader, MalformedSettings}

import scala.language.postfixOps

class EsConfigFileLoaderTest extends WordSpec {
  private implicit val envVarsProvider: EnvVarsProvider = name =>
    name.value.value match {
      case "USER_NAME" => Some("John")
      case _ => None
    }

  "FileConfigLoader" should {
    "decode file file" in {
      val result = loadFromTempFile[String](""""encoded value"""")
      result shouldBe "encoded value".asRight
    }
    "decode file with variable" in {
      val result = loadFromTempFile[String](""""${USER_NAME}"""")
      result shouldBe "John".asRight
    }
    "fail for non existing vairable" in {
      val result = loadFromTempFile[String](""""${WRONG_VARIABLE}"""")
      result shouldBe a[Left[MalformedSettings, _]]
      result.left.get.message should include("WRONG_VARIABLE")
    }
  }

  private def loadFromTempFile[A: Decoder](content: String) =
    tempFile(content).map { file =>
      createFileConfigLoader[A]
        .loadConfigFromFile(file, "TEST")
    }.get

  private def tempFile(content: String) = File.temporaryFile().map(_.write(content))

  private def createFileConfigLoader[A: Decoder] = new EsConfigFileLoader[A]()
}

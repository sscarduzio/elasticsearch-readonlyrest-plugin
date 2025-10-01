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
import cats.implicits.*
import io.circe.Decoder
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.SystemContext
import tech.beshu.ror.settings.es.YamlFileBasedSettingsLoader

class YamlFileBasedRorSettingsLoaderTest extends AnyWordSpec with Inside {

  private implicit val systemContext: SystemContext = new SystemContext(
    envVarsProvider = name =>
      name.value.value match {
        case "USER_NAME" => Some("John")
        case _ => None
      }
  )

  "YamlFileBasedConfigLoader" should {
    "decode file file" in {
      val result = loadFromTempFile[String](""""encoded value"""")
      result shouldBe "encoded value".asRight
    }
    "decode file with variable" in {
      val result = loadFromTempFile[String](""""${USER_NAME}"""")
      result shouldBe "John".asRight
    }
    "decode file with variable with transformation" in {
      val result = loadFromTempFile[String](""""${USER_NAME}#{to_uppercase}"""")
      result shouldBe "JOHN".asRight
    }
    "fail for non existing vairable" in {
      val result = loadFromTempFile[String](""""${WRONG_VARIABLE}"""")
      inside(result) {
        case Left(error) =>
          error.message should include("WRONG_VARIABLE")
      }
    }
  }

  private def loadFromTempFile[A: Decoder](content: String) =
    tempFile(content).map { file =>
      createFileConfigLoader(file)
        .loadSettings[A]("TEST")
    }.get()

  private def tempFile(content: String) = File.temporaryFile().map(_.write(content))

  private def createFileConfigLoader(file: File) = new YamlFileBasedSettingsLoader(file)
}

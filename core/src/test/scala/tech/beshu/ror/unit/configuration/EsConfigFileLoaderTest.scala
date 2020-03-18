package tech.beshu.ror.unit.configuration

import better.files.File
import io.circe.Decoder
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.configuration.{EsConfigFileLoader, MalformedSettings}
import tech.beshu.ror.providers.EnvVarsProvider

class EsConfigFileLoaderTest
  extends WordSpec {
  private implicit val envVarsProvider: EnvVarsProvider = name =>
    name.value.value match {
      case "USER_NAME" => Some("John")
      case _ => None
    }

  "FileConfigLoader" should {
    "decode file file" in {
      val result = loadFromTempFile[String](""""encoded value"""")
      result.right.get shouldBe "encoded value"
    }
    "decode file with variable" in {
      val result = loadFromTempFile[String](""""${USER_NAME}"""")
      result.right.get shouldBe "John"
    }
    "fail for non existing vairable" in {
      val result = loadFromTempFile[String](""""${WRONG_VARIABLE}"""")
      result.left.get shouldBe a[MalformedSettings]
      result.left.get.message should include("WRONG_VARIABLE")
    }
  }

  private def loadFromTempFile[A: Decoder](content: String) = tempFile(content).map { file =>
    createFileConfigLoader[A]
      .loadConfigFromFile(file, "TEST")
  }.get

  private def tempFile(content: String) = File.temporaryFile().map(_.write(content))

  private def createFileConfigLoader[A: Decoder] = new EsConfigFileLoader[A]()
}

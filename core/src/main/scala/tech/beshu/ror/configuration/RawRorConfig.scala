package tech.beshu.ror.configuration

import org.yaml.snakeyaml.Yaml
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.{ReadonlyrestSettingsCreationError, UnparsableYamlContent}
import tech.beshu.ror.acl.factory.CoreFactory.{AclCreationError, Attributes}

import scala.util.{Failure, Success, Try}

final case class RawRorConfig private (validatedRorYaml: String) {

  private def trimToRorPartOnly(settingsYamlString: String): Either[AclCreationError, String] = {
    val yaml = new Yaml()
    Try(yaml.load[java.util.Map[String, Object]](settingsYamlString)) match {
      case Success(map) =>
        Option(map.get("readonlyrest")).map(yaml.dump) match { // todo: "readonlyrest" from const
          case Some(value) => Right(value)
          case None => Left(ReadonlyrestSettingsCreationError(Message(s"No ${"readonlyrest"} section found"))) // fixme:
        }
      case Failure(ex) =>
        Left(UnparsableYamlContent(Message(s"Malformed: $settingsYamlString")))
    }
  }
}

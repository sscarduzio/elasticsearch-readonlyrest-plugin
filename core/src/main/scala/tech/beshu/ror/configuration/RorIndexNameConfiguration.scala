package tech.beshu.ror.configuration

import java.io.{File => JFile}
import java.nio.file.Path

import better.files.File
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers._

final case class RorIndexNameConfiguration(name: IndexName)

object RorIndexNameConfiguration extends LoadFromEsConfig {

  private val defaultIndexName = IndexName.fromUnsafeString(".readonlyrest")

  def load(esConfig: File): Task[Either[MalformedSettings, RorIndexNameConfiguration]] = Task {
    loadConfigFromFile[RorIndexNameConfiguration](esConfig, "Custom ROR index name")
  }

  def load(esConfigFolderPath: Path): Task[Either[MalformedSettings, RorIndexNameConfiguration]] = {
    load(File(new JFile(esConfigFolderPath.toFile, "elasticsearch.yml").toPath))
  }

  private implicit val rorIndexNameConfigurationDecoder: Decoder[RorIndexNameConfiguration] = {
    Decoder.instance { c =>
      val oneLine = c.downField("readonlyrest.custom_index_name").as[Option[NonEmptyString]]
      val twoLines =  c.downField("readonlyrest").downField("custom_index_name").as[Option[NonEmptyString]]
      val customIndexName = (oneLine.toOption.flatten, twoLines.toOption.flatten) match {
        case (Some(result), _) => IndexName(result)
        case (_, Some(result)) => IndexName(result)
        case (_, _) => defaultIndexName
      }
      Right(RorIndexNameConfiguration(customIndexName))
    }
  }
}

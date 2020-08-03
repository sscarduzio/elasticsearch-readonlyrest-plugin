package tech.beshu.ror.configuration.loader.distributed.dto

import cats.implicits._
import io.circe.generic.extras.ConfiguredJsonCodec
import tech.beshu.ror.configuration.loader.distributed.Summary
import tech.beshu.ror.configuration.loader.distributed.Summary.{Error, Result}

@ConfiguredJsonCodec
final case class ResultDTO(config: Option[LoadedConfigDTO], warnings: List[NodesResponseWaringDTO], error: Option[String])

object ResultDTO {
  def create(o: Either[Error, Result]): ResultDTO =
    o.bimap(createError, createResult).merge

  private def createResult(result: Result) =
    ResultDTO(LoadedConfigDTO.create(result.config).some, result.warnings.map(NodesResponseWaringDTO.create), None)

  private def createError(error: Error) = {
    val message = error match {
      case Summary.CurrentNodeResponseError(detailedMessage) => s"current node response error: $detailedMessage"
      case Summary.CurrentNodeConfigError(error) => s"current node returned error: ${LoadedConfigError.createError(error)}"
      case Summary.CurrentNodeResponseTimeoutError => "current node response timeout"
    }
    ResultDTO(None, Nil, message.some)
  }
}
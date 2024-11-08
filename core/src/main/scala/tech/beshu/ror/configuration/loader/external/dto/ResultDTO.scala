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
package tech.beshu.ror.configuration.loader.external.dto

import cats.implicits.*
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import tech.beshu.ror.configuration.loader.distributed.Summary
import tech.beshu.ror.configuration.loader.distributed.Summary.{Error, Result}

final case class ResultDTO(config: Option[LoadedConfigDTO],
                           warnings: List[NodesResponseWaringDTO],
                           error: Option[String])

object ResultDTO {
  def create(o: Either[Error, Result]): ResultDTO =
    o.bimap(createError, createResult).merge

  private def createResult(result: Result) =
    ResultDTO(LoadedConfigDTO.create(result.config).some, result.warnings.map(NodesResponseWaringDTO.create), None)

  private def createError(error: Error) = {
    val message = error match {
      case Summary.CurrentNodeResponseError(detailedMessage) => s"current node response error: ${detailedMessage.show}"
      case Summary.CurrentNodeConfigError(error) => show"current node returned error: ${error.show}"
      case Summary.CurrentNodeResponseTimeoutError => "current node response timeout"
    }
    ResultDTO(None, Nil, message.some)
  }

  implicit val codec: Codec[ResultDTO] = deriveCodec
}
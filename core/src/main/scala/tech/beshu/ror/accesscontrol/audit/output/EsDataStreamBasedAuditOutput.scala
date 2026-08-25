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
package tech.beshu.ror.accesscontrol.audit.output

import cats.data.{EitherT, NonEmptyList}
import monix.eval.Task
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.audit.JsonAuditSerializer
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, AuditOutputName, RequestId, RorAuditDataStream}
import tech.beshu.ror.audit.AuditResponseContext
import tech.beshu.ror.es.services.DataStreamBasedAuditOutputService
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.ScalaOps.value

private[audit] final class EsDataStreamBasedAuditOutput private (
    outputName: AuditOutputName,
    serializer: JsonAuditSerializer,
    rorAuditDataStream: RorAuditDataStream,
    auditOutputService: DataStreamBasedAuditOutputService
) extends JsonBasedAuditOutput(outputName, serializer) {

  override protected def submit(event: AuditResponseContext, serializedEvent: JSONObject)(
      implicit requestId: RequestId
  ): Task[Unit] = Task {
    auditOutputService.submit(
      dataStreamName = rorAuditDataStream.dataStream,
      documentId = event.requestContext.id,
      jsonRecord = serializedEvent.toString
    )
  }

  override def close(): Task[Unit] = Task.delay(auditOutputService.close())

}

object EsDataStreamBasedAuditOutput {

  final case class CreationError private (message: String) extends AnyVal

  object CreationError {

    def apply(errors: NonEmptyList[AuditDataStreamCreator.ErrorMessage], auditCluster: AuditCluster): CreationError = {
      val clusterType = auditCluster match {
        case AuditCluster.LocalAuditCluster               => "local cluster"
        case AuditCluster.RemoteAuditCluster(nodes, _, _) => s"remote cluster ${nodes.toList.map(_.uri).show}"
      }
      new CreationError(
        s"Unable to configure audit output using a data stream in $clusterType. Details: [${errors.toList.map(_.message).show}]"
      )
    }

  }

  def create(
      outputName: AuditOutputName,
      serializer: JsonAuditSerializer,
      rorAuditDataStream: RorAuditDataStream,
      auditOutputService: DataStreamBasedAuditOutputService,
      auditCluster: AuditCluster
  ): Task[Either[CreationError, EsDataStreamBasedAuditOutput]] = value {
    for {
      _ <- createRorAuditDataStreamIfNotExists(rorAuditDataStream, auditOutputService, auditCluster)
      auditOutput <- createAuditOutput(outputName, serializer, rorAuditDataStream, auditOutputService)
    } yield auditOutput
  }

  private def createRorAuditDataStreamIfNotExists(
      rorAuditDataStream: RorAuditDataStream,
      auditOutputService: DataStreamBasedAuditOutputService,
      auditCluster: AuditCluster
  ) = {
    EitherT(auditOutputService.dataStreamCreator.createIfNotExists(rorAuditDataStream))
      .leftMap(errorMessages => CreationError(errorMessages, auditCluster))
  }

  private def createAuditOutput(
      outputName: AuditOutputName,
      serializer: JsonAuditSerializer,
      rorAuditDataStream: RorAuditDataStream,
      auditOutputService: DataStreamBasedAuditOutputService
  ) = {
    EitherT.right[CreationError](
      Task.delay(
        new EsDataStreamBasedAuditOutput(outputName, serializer, rorAuditDataStream, auditOutputService)
      )
    )
  }

}

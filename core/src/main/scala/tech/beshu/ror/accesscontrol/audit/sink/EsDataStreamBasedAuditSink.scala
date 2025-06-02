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
package tech.beshu.ror.accesscontrol.audit.sink

import cats.data.{EitherT, NonEmptyList}
import monix.eval.Task
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorAuditDataStream}
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditLogSerializer, AuditResponseContext}
import tech.beshu.ror.es.DataStreamBasedAuditSinkService
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.ScalaOps.value

private[audit] final class EsDataStreamBasedAuditSink private(serializer: AuditLogSerializer,
                                                              environmentContext: AuditEnvironmentContext,
                                                              rorAuditDataStream: RorAuditDataStream,
                                                              auditSinkService: DataStreamBasedAuditSinkService)
  extends BaseAuditSink(serializer, environmentContext) {

  override protected def submit(event: AuditResponseContext, serializedEvent: JSONObject): Task[Unit] = Task {
    auditSinkService.submit(
      dataStreamName = rorAuditDataStream.dataStream,
      documentId = event.requestContext.id,
      jsonRecord = serializedEvent.toString
    )
  }

  override def close(): Task[Unit] = Task.delay(auditSinkService.close())

}

object EsDataStreamBasedAuditSink {

  final case class CreationError private(message: String) extends AnyVal
  object CreationError {
    def apply(errors: NonEmptyList[AuditDataStreamCreator.ErrorMessage], auditCluster: AuditCluster): CreationError = {
      val clusterType = auditCluster match {
        case AuditCluster.LocalAuditCluster => "local cluster"
        case AuditCluster.RemoteAuditCluster(uris) => s"remote cluster ${uris.toList.show}"
      }
      new CreationError(s"Unable to configure audit output using a data stream in $clusterType. Details: [${errors.toList.map(_.message).show}]")
    }
  }

  def create(serializer: AuditLogSerializer,
             environmentContext: AuditEnvironmentContext,
             rorAuditDataStream: RorAuditDataStream,
             auditSinkService: DataStreamBasedAuditSinkService,
             auditCluster: AuditCluster): Task[Either[CreationError, EsDataStreamBasedAuditSink]] = value {
    for {
      _ <- createRorAuditDataStreamIfNotExists(rorAuditDataStream, auditSinkService, auditCluster)
      auditSink <- createAuditSink(serializer, environmentContext, rorAuditDataStream, auditSinkService)
    } yield auditSink
  }

  private def createRorAuditDataStreamIfNotExists(rorAuditDataStream: RorAuditDataStream,
                                                  auditSinkService: DataStreamBasedAuditSinkService,
                                                  auditCluster: AuditCluster) = {
    EitherT(auditSinkService.dataStreamCreator.createIfNotExists(rorAuditDataStream))
      .leftMap(errorMessages => CreationError(errorMessages, auditCluster))
  }

  private def createAuditSink(serializer: AuditLogSerializer,
                              environmentContext: AuditEnvironmentContext,
                              rorAuditDataStream: RorAuditDataStream,
                              auditSinkService: DataStreamBasedAuditSinkService) = {
    EitherT.right[CreationError](Task.delay(
      new EsDataStreamBasedAuditSink(serializer, environmentContext, rorAuditDataStream, auditSinkService)
    ))
  }
}

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
package tech.beshu.ror.accesscontrol.audit

import monix.eval.Task
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.audit.EsIndexBasedAuditSink.EsNodeDetailsReporting
import tech.beshu.ror.accesscontrol.domain.RorAuditIndexTemplate
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}
import tech.beshu.ror.configuration.EsNodeConfig
import tech.beshu.ror.es.AuditSinkService

import java.time.{Clock, Instant}

private[audit] class EsIndexBasedAuditSink private(serializer: AuditLogSerializer,
                                                   rorAuditIndexTemplate: RorAuditIndexTemplate,
                                                   auditSinkService: AuditSinkService,
                                                   esNodeDetailsReporting: EsNodeDetailsReporting)
                                                  (implicit clock: Clock)
  extends BaseAuditSink(serializer) {

  override protected def submit(event: AuditResponseContext, serializedEvent: JSONObject): Task[Unit] = Task {
    val enrichedEvent = esNodeDetailsReporting match {
      case EsNodeDetailsReporting.Disabled =>
        serializedEvent
      case EsNodeDetailsReporting.Enabled(esNodeConfig) =>
        serializedEvent
          .put("es_cluster_name", esNodeConfig.clusterName)
          .put("es_node_name", esNodeConfig.nodeName)
    }
    auditSinkService.submit(
      rorAuditIndexTemplate.indexName(Instant.now(clock)).name.value,
      event.requestContext.id,
      enrichedEvent.toString
    )
  }

  override def close(): Task[Unit] = Task.delay(auditSinkService.close())
}

object EsIndexBasedAuditSink {

  sealed trait EsNodeDetailsReporting

  object EsNodeDetailsReporting {
    case object Disabled extends EsNodeDetailsReporting

    final case class Enabled(esNodeConfig: EsNodeConfig) extends EsNodeDetailsReporting
  }

  def apply(serializer: AuditLogSerializer,
            indexTemplate: RorAuditIndexTemplate,
            auditSinkService: AuditSinkService,
            esNodeDetailsReporting: EsNodeDetailsReporting)
           (implicit clock: Clock): EsIndexBasedAuditSink = {
    new EsIndexBasedAuditSink(serializer, indexTemplate, auditSinkService, esNodeDetailsReporting)
  }
}

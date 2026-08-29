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
package tech.beshu.ror.mocks

import cats.effect.Resource
import monix.eval.Task
import tech.beshu.ror.accesscontrol.audit.EsAuditCapabilities
import tech.beshu.ror.accesscontrol.audit.output.{
  AuditDataStreamCreator,
  DataStreamBasedAuditOutputServiceCreator,
  IndexBasedAuditOutputServiceCreator
}
import tech.beshu.ror.accesscontrol.domain.AuditCluster
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, IndexName, RequestId}
import tech.beshu.ror.es.services.{DataStreamBasedAuditOutputService, IndexBasedAuditOutputService}

object MockedCapabilities {

  val indexOrDataStream: EsAuditCapabilities.IndexOrDataStream =
    new EsAuditCapabilities.IndexOrDataStream(
      MockIndexBasedAuditOutputServiceCreator,
      MockDataStreamBasedAuditOutputServiceCreator
    )

  val indexOnly: EsAuditCapabilities.IndexOnly =
    new EsAuditCapabilities.IndexOnly(MockIndexBasedAuditOutputServiceCreator)
}

object MockIndexBasedAuditOutputServiceCreator extends IndexBasedAuditOutputServiceCreator {

  override def index(cluster: AuditCluster): IndexBasedAuditOutputService = new IndexBasedAuditOutputService {
    override def submit(indexName: IndexName.Full, documentId: String, jsonRecord: String)(
        implicit requestId: RequestId
    ): Unit =
      throw new IllegalStateException("Cannot use it. It's just a mock")
    override def close(): Unit = ()
  }

}

object MockDataStreamBasedAuditOutputServiceCreator extends DataStreamBasedAuditOutputServiceCreator {

  override def dataStream(cluster: AuditCluster): DataStreamBasedAuditOutputService =
    new DataStreamBasedAuditOutputService {
      override def submit(dataStreamName: DataStreamName.Full, documentId: String, jsonRecord: String)(
          implicit requestId: RequestId
      ): Unit =
        throw new IllegalStateException("Cannot use it. It's just a mock")
      override def dataStreamCreator: Resource[Task, AuditDataStreamCreator] =
        throw new IllegalStateException("Cannot use it. It's just a mock")
      override def close(): Unit = ()
    }

}

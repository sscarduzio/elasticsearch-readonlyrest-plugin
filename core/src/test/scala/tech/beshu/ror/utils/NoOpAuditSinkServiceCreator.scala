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
package tech.beshu.ror.utils

import tech.beshu.ror.accesscontrol.audit.sink.{AuditDataStreamCreator, DataStreamAndIndexBasedAuditSinkServiceCreator}
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, DataStreamName, IndexName, RequestId}
import tech.beshu.ror.es.services.{DataStreamBasedAuditSinkService, IndexBasedAuditSinkService}

object NoOpAuditSinkServiceCreator extends DataStreamAndIndexBasedAuditSinkServiceCreator {

  def index(cluster: AuditCluster): IndexBasedAuditSinkService = {
    println("MARKER")
    new IndexBasedAuditSinkService {
      override def submit(indexName: IndexName.Full, documentId: String, jsonRecord: String)(
          implicit requestId: RequestId
      ): Unit = ()

      override def close(): Unit = ()
    }
  }

  override def dataStream(cluster: AuditCluster): DataStreamBasedAuditSinkService = {
    println("MARKER")
    new DataStreamBasedAuditSinkService {
      override def submit(dataStreamName: DataStreamName.Full, documentId: String, jsonRecord: String)(
          implicit requestId: RequestId
      ): Unit = ()

      override def dataStreamCreator: AuditDataStreamCreator = ???

      override def close(): Unit = ()
    }
  }

}

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
package tech.beshu.ror.accesscontrol.utils

import java.util.concurrent.atomic.AtomicReference

import com.unboundid.ldap.sdk._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging

import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

class LdapConnectionPoolOps(connectionPool: LDAPConnectionPool)
                           (implicit blockingScheduler: Scheduler)
  extends Logging {

  def asyncBind(request: BindRequest): Task[BindResult] = {
    Task(connectionPool.getConnection)
      .bracket(
        use = connection => Task(connection.bind(request))
      )(
        release = connection => Task(connectionPool.releaseConnection(connection))
      )
      .executeOn(blockingScheduler)
      .asyncBoundary
  }

  def process(requestCreator: AsyncSearchResultListener => LDAPRequest,
              timeout: FiniteDuration Refined Positive): Task[Either[SearchResult, List[SearchResultEntry]]] = {
    val searchResultListener = new LdapConnectionPoolOps.UnboundidSearchResultListener
    Task(requestCreator(searchResultListener))
      .map(request => connectionPool.processRequestsAsync((request :: Nil).asJava, timeout.value.toMillis))
      .executeOn(blockingScheduler)
      .flatMap { results =>
        results.asScala.toList match {
          case Nil => throw new IllegalStateException("LDAP - expected at least one result")
          case requestId :: _ =>
            if(requestId.isCancelled) Task.now(Left(new SearchResult(requestId.get())))
            else searchResultListener.result
        }
      }
      .asyncBoundary
  }
}

object LdapConnectionPoolOps {
  implicit def toOps(connectionPool: LDAPConnectionPool)
                    (implicit blockingScheduler: Scheduler): LdapConnectionPoolOps =
    new LdapConnectionPoolOps(connectionPool)

  private class UnboundidSearchResultListener extends com.unboundid.ldap.sdk.AsyncSearchResultListener {
    private val searchResultEntries = new AtomicReference(List.empty[SearchResultEntry])

    private val promise = Promise[Either[SearchResult, List[SearchResultEntry]]]

    override def searchResultReceived(requestID: AsyncRequestID, searchResult: SearchResult): Unit = {
      if (ResultCode.SUCCESS == searchResult.getResultCode) {
        promise.success(Right(searchResultEntries.get()))
      } else {
        promise.success(Left(searchResult))
      }
    }

    override def searchEntryReturned(searchEntry: SearchResultEntry): Unit = {
      searchResultEntries.getAndUpdate { searchResultEntries: List[SearchResultEntry] =>
        searchEntry :: searchResultEntries
      }
    }

    override def searchReferenceReturned(searchReference: SearchResultReference): Unit = {
      // nothing to do
    }

    def result: Task[Either[SearchResult, List[SearchResultEntry]]] = Task.fromFuture(promise.future)
  }

}
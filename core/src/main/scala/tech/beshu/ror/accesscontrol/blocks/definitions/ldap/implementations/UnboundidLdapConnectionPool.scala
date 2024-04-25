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
package tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations

import com.unboundid.ldap.sdk._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.BindRequestUser
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Promise
import scala.jdk.CollectionConverters._

class UnboundidLdapConnectionPool(connectionPool: LDAPConnectionPool,
                                  bindRequestUser: BindRequestUser)
  extends Logging {

  def asyncBind(request: BindRequest): Task[BindResult] = {
    bindRequestUser match {
      case BindRequestUser.Anonymous => Task(connectionPool.bind(request))
      case BindRequestUser.CustomUser(_, _) => Task(connectionPool.bindAndRevertAuthentication(request))
    }
  }

  def process(requestCreator: AsyncSearchResultListener => LDAPRequest,
              timeout: PositiveFiniteDuration): Task[Either[SearchResult, List[SearchResultEntry]]] = {
    val searchResultListener = new UnboundidLdapConnectionPool.UnboundidSearchResultListener
    Task(requestCreator(searchResultListener))
      .map(request => connectionPool.processRequestsAsync((request :: Nil).asJava, timeout.value.toMillis))
      .flatMap { results =>
        results.asScala.toList match {
          case Nil => throw new IllegalStateException("LDAP - expected at least one result")
          case requestId :: _ =>
            if (requestId.isCancelled) Task.now(Left(new SearchResult(requestId.get())))
            else searchResultListener.result
        }
      }
  }

  def close(): Task[Unit] = {
    Task.delay(connectionPool.close())
  }

}

object UnboundidLdapConnectionPool {

  private class UnboundidSearchResultListener extends com.unboundid.ldap.sdk.AsyncSearchResultListener {
    private val searchResultEntries = new AtomicReference(List.empty[SearchResultEntry])

    private val promise = Promise[Either[SearchResult, List[SearchResultEntry]]]()

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
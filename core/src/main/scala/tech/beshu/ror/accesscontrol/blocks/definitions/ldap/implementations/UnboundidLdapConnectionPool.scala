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

import com.unboundid.ldap.sdk.*
import monix.catnap.Semaphore
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.BindRequestUser
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.RefinedUtils.PositiveFiniteDuration
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.jdk.CollectionConverters.*

class UnboundidLdapConnectionPool private (
    connectionPool: LDAPConnectionPool,
    bindRequestUser: BindRequestUser,
    callPermits: Semaphore[Task]
) extends RequestIdAwareLogging {

  import UnboundidLdapConnectionPool.*

  def asyncBind(request: BindRequest): Task[BindResult] = blockingSdkCall {
    bindRequestUser match {
      case BindRequestUser.Anonymous        => Task(connectionPool.bind(request))
      case BindRequestUser.CustomUser(_, _) => Task(connectionPool.bindAndRevertAuthentication(request))
    }
  }

  def process(
      requestCreator: AsyncSearchResultListener => LDAPRequest,
      timeout: PositiveFiniteDuration
  ): Task[Either[SearchResult, List[SearchResultEntry]]] = {
    processSearch(requestCreator, timeout).map {
      case SearchOutcome.Completed(result, entries) if result.getResultCode == ResultCode.SUCCESS => Right(entries)
      case SearchOutcome.Completed(result, _)                                                     => Left(result)
      case SearchOutcome.Aborted(result) => Left(new SearchResult(result))
    }
  }

  def close(): Task[Unit] = {
    // The SDK close blocks until the pool threads stop.
    onBlockingScheduler(Task.delay(connectionPool.close()))
  }

  private def processSearch(
      requestCreator: AsyncSearchResultListener => LDAPRequest,
      timeout: PositiveFiniteDuration
  ): Task[SearchOutcome] = blockingSdkCall {
    val searchResultListener = new UnboundidSearchResultListener
    Task(requestCreator(searchResultListener))
      .map(request => connectionPool.processRequestsAsync((request :: Nil).asJava, timeout.value.toMillis))
      .flatMap { results =>
        results.asScala.toList match {
          case Nil            => Task.raiseError(new IllegalStateException("LDAP - expected at least one result"))
          case requestId :: _ =>
            // processRequestsAsync returns when each request has a result. The SDK gives that result to
            // the listener only when the server answers or the connection closes. A cancelled request
            // (timeout) or a request that the SDK could not send has its result only in the request ID.
            if (requestId.isCancelled) Task.delay(SearchOutcome.Aborted(requestId.get()))
            else
              searchResultListener.result match {
                case Some(result) => Task.now(SearchOutcome.Completed(result, searchResultListener.entries))
                case None         => Task.delay(SearchOutcome.Aborted(requestId.get()))
              }
        }
      }
  }

  // Runs a blocking SDK call on the blocking scheduler, with no more parallel calls than the pool size.
  // Without the limit, a slow LDAP server gets a new connection for each extra call.
  // A cancelled call keeps its permit until the SDK call ends, because cancellation cannot stop a blocked thread.
  // A call that is cancelled while it waits for a permit does not start the SDK call.
  private def blockingSdkCall[A](call: Task[A]): Task[A] = Task.defer {
    val cancelled = new AtomicBoolean(false)
    callPermits
      .withPermit(Task.defer {
        if (cancelled.get()) Task.now(Option.empty[A])
        else onBlockingScheduler(call).map(Some(_))
      })
      .uncancelable
      .doOnCancel(Task(cancelled.set(true)))
      .flatMap {
        case Some(result) => Task.now(result)
        // Only a cancelled call skips the SDK call, so nobody waits for this result.
        // An error here would be reported as an uncaught failure.
        case None => Task.never
      }
  }

  private def onBlockingScheduler[A](call: Task[A]): Task[A] =
    doPrivileged(call)(RorSchedulers.blockingScheduler)
}

object UnboundidLdapConnectionPool {

  def create(
      connectionPool: LDAPConnectionPool,
      bindRequestUser: BindRequestUser
  ): Task[UnboundidLdapConnectionPool] = {
    Semaphore[Task](connectionPool.getMaximumAvailableConnections.toLong)
      .map(new UnboundidLdapConnectionPool(connectionPool, bindRequestUser, _))
  }

  private sealed trait SearchOutcome

  private object SearchOutcome {
    final case class Completed(searchResult: SearchResult, entries: List[SearchResultEntry]) extends SearchOutcome
    final case class Aborted(ldapResult: LDAPResult) extends SearchOutcome
  }

  private class UnboundidSearchResultListener extends com.unboundid.ldap.sdk.AsyncSearchResultListener {
    private val searchResultEntries = new AtomicReference(List.empty[SearchResultEntry])
    private val searchResult = new AtomicReference(Option.empty[SearchResult])

    override def searchResultReceived(requestID: AsyncRequestID, searchResult: SearchResult): Unit = {
      this.searchResult.set(Some(searchResult))
    }

    override def searchEntryReturned(searchEntry: SearchResultEntry): Unit = {
      searchResultEntries.getAndUpdate { (searchResultEntries: List[SearchResultEntry]) =>
        searchEntry :: searchResultEntries
      }
    }

    override def searchReferenceReturned(searchReference: SearchResultReference): Unit = {
      // nothing to do
    }

    def result: Option[SearchResult] = searchResult.get()

    def entries: List[SearchResultEntry] = searchResultEntries.get()
  }

}

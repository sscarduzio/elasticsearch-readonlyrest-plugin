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
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.BindRequestUser
import tech.beshu.ror.utils.RefinedUtils.PositiveFiniteDuration
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

class UnboundidLdapConnectionPool(connectionPool: LDAPConnectionPool, bindRequestUser: BindRequestUser)
    extends RequestIdAwareLogging {

  import UnboundidLdapConnectionPool.*

  def asyncBind(request: BindRequest): Task[BindResult] = {
    bindRequestUser match {
      case BindRequestUser.Anonymous        => Task(connectionPool.bind(request))
      case BindRequestUser.CustomUser(_, _) => Task(connectionPool.bindAndRevertAuthentication(request))
    }
  }

  def process(
      requestCreator: AsyncSearchResultListener => LDAPRequest,
      timeout: PositiveFiniteDuration
  ): Task[Either[SearchResult, List[SearchResultEntry]]] = {
    UnboundidAsyncSearch.run(connectionPool, requestCreator, timeout).map {
      case SearchOutcome.Completed(result, entries) if result.getResultCode == ResultCode.SUCCESS => Right(entries)
      case SearchOutcome.Completed(result, _)                                                     => Left(result)
      case SearchOutcome.Aborted(result) => Left(new SearchResult(result))
    }
  }

  def close(): Task[Unit] = {
    Task.delay(connectionPool.close())
  }

}

object UnboundidLdapConnectionPool {

  private sealed trait SearchOutcome

  private object SearchOutcome {
    final case class Completed(searchResult: SearchResult, entries: List[SearchResultEntry]) extends SearchOutcome
    // The search timed out, or the SDK could not send it.
    final case class Aborted(ldapResult: LDAPResult) extends SearchOutcome
  }

  private object UnboundidAsyncSearch {

    def run(
        connectionPool: LDAPConnectionPool,
        requestCreator: AsyncSearchResultListener => LDAPRequest,
        timeout: PositiveFiniteDuration
    ): Task[SearchOutcome] = {
      val searchResultListener = new UnboundidSearchResultListener
      Task(requestCreator(searchResultListener))
        .map(request => connectionPool.processRequestsAsync((request :: Nil).asJava, timeout.value.toMillis))
        .flatMap { results =>
          results.asScala.toList match {
            case Nil            => throw new IllegalStateException("LDAP - expected at least one result")
            case requestId :: _ =>
              // processRequestsAsync returns when each request has a result. The SDK gives that result to
              // the listener only when the server answers or the connection closes. A cancelled request
              // (timeout) or a request that the SDK could not send has its result only in the request ID.
              if (requestId.isCancelled) Task.now(SearchOutcome.Aborted(requestId.get()))
              else
                searchResultListener.result match {
                  case Some(result) => Task.now(SearchOutcome.Completed(result, searchResultListener.entries))
                  case None         => Task.delay(SearchOutcome.Aborted(requestId.get()))
                }
          }
        }
    }

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

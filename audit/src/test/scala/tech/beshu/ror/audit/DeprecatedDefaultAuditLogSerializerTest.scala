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
package tech.beshu.ror.audit

import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.commons.ResponseContext.FinalState
import tech.beshu.ror.commons.shims.request.RequestContextShim
import tech.beshu.ror.commons.{ResponseContext, Verbosity}
import tech.beshu.ror.requestcontext.DefaultAuditLogSerializer

import java.util
import java.util.concurrent.{Callable, Executors, TimeUnit}
import java.util.{Date, Optional}
import scala.annotation.nowarn

class DeprecatedDefaultAuditLogSerializerTest extends AnyWordSpec {

  @nowarn("cat=deprecation")
  private val serializer = new DefaultAuditLogSerializer

  "The deprecated DefaultAuditLogSerializer" should {
    "write the timestamp in UTC" in {
      timestampOf(new Date(1792569167000L)) should be("2026-10-21T07:52:47Z")
    }
    "write correct timestamps when many threads use it at the same time" in {
      val dates = (0 until 1000).map(i => new Date(1792569167000L + i * 3600123L))
      val expected = dates.map(timestampOf)
      val pool = Executors.newFixedThreadPool(8)
      try {
        val results = (1 to 8).map { _ =>
          pool.submit(new Callable[Seq[String]] {
            override def call(): Seq[String] = (1 to 20).flatMap(_ => dates.map(timestampOf))
          })
        }
        results.foreach(_.get(1, TimeUnit.MINUTES) should be(Seq.fill(20)(expected).flatten))
      } finally {
        pool.shutdown()
      }
    }
  }

  private def timestampOf(date: Date): String = {
    val responseContext =
      new ResponseContext(FinalState.ALLOWED, new StubRequestContext(date), null, Verbosity.INFO, "reason", true)
    serializer.createLoggableEntry(responseContext).get("@timestamp").toString
  }

  private class StubRequestContext(timestamp: Date) extends RequestContextShim {
    override def getId: String = "id"
    override def getCorrelationId: String = "correlation-id"
    override def getIndices: util.Set[String] = util.Collections.emptySet()
    override def getTimestamp: Date = timestamp
    override def getAction: String = "indices:data/read/search"
    override def getHeaders: util.Map[String, String] = util.Collections.emptyMap()
    override def getUri: String = "/_search"
    override def getHistoryString: String = ""
    override def getContent: String = ""
    override def getContentLength: Integer = 0
    override def getRemoteAddress: String = "127.0.0.1"
    override def getLocalAddress: String = "127.0.0.1"
    override def getType: String = "SearchRequest"
    override def getTaskId: java.lang.Long = 1L
    override def getMethodString: String = "GET"
    override def getLoggedInUserName: Optional[String] = Optional.empty()
    override def involvesIndices: Boolean = false
  }

}

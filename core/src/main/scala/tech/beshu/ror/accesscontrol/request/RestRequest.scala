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
package tech.beshu.ror.accesscontrol.request

import cats.Eval
import squants.information.Information
import tech.beshu.ror.accesscontrol.domain.Header.findSingleHeader
import tech.beshu.ror.accesscontrol.domain.{Address, CorrelationId, Header, UriPath}
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.utils.uniquelist.UniqueList

trait RestRequest {
  def method: Method
  def path: UriPath

  def allHeaders: UniqueList[Header]

  def localAddress: Address
  def remoteAddress: Option[Address]

  def content: String
  def contentLength: Information

  lazy val correlationId: Eval[CorrelationId] = Eval.later {
    findSingleHeader(Header.Name.correlationId, in = this.allHeaders) match {
      case Right(Some(header))   => CorrelationId(header.value)
      case Left(_) | Right(None) => CorrelationId.random
    }
  }

}

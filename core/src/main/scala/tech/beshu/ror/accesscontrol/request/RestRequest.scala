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
import cats.implicits.*
import eu.timepit.refined.types.string.NonEmptyString
import squants.information.Information
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix.StrictlyDefined
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenPrefix.bearer
import tech.beshu.ror.accesscontrol.domain.Header.findSingleHeader
import tech.beshu.ror.accesscontrol.domain.{
  Address,
  AuthorizationToken,
  AuthorizationTokenDef,
  CorrelationId,
  Header,
  RequestId,
  RorKbnLicenseType,
  UriPath,
  User
}
import tech.beshu.ror.accesscontrol.request.RequestContext.AuthorizationTokenRetrievingError.{
  InvalidValue,
  MissingHeader
}
import tech.beshu.ror.accesscontrol.request.RequestContext.{AuthorizationTokenRetrievingError, Method}
import tech.beshu.ror.utils.uniquelist.UniqueList

trait RestRequest {
  def method: Method
  def path: UriPath

  def allHeaders: UniqueList[Header]

  def localAddress: Address
  def remoteAddress: Option[Address]

  def content: String
  def contentLength: Information
}

object RestRequest {

  extension (restRequest: RestRequest) {

    def correlationId: Eval[CorrelationId] = Eval.later {
      findSingleHeader(Header.Name.correlationId, in = restRequest.allHeaders) match {
        case Right(Some(header))   => CorrelationId(header.value)
        case Left(_) | Right(None) => CorrelationId.random
      }
    }

    def impersonateAs(
        implicit id: RequestId
    ): Option[User.Id] = {
      singleHeaderOf(Header.Name.impersonateAs)
        .map { header => User.Id(header.value) }
    }

    /** Returns the first entry of the forwarded chain, which is the client address, and `None` when no
     * entry parses. X-Forwarded-For repeats by design, so two values are not an ambiguity to reject.
     */
    def xForwardedForHeaderValue: Option[Address] = {
      restRequest.allHeaders.view
        .filter(_.name === Header.Name.xForwardedFor)
        .flatMap(_.value.value.split(",").toList)
        .map(_.trim)
        .flatMap(Address.from)
        .headOption
    }

    def userAgent: Option[NonEmptyString] =
      findSingleHeader(Header.Name.userAgent, in = restRequest.allHeaders).toOption.flatten.map(_.value)

    def rawAuthHeader(
        implicit id: RequestId
    ): Option[Header] = singleHeaderOf(Header.Name.authorization)

    def bearerToken(
        implicit id: RequestId
    ): Either[AuthorizationTokenRetrievingError, AuthorizationToken] =
      authorizationTokenBy(
        AuthorizationTokenDef(headerName = Header.Name.authorization, allowedPrefix = StrictlyDefined(bearer))
      )

    def authorizationTokenBy(config: AuthorizationTokenDef)(
        implicit id: RequestId
    ): Either[AuthorizationTokenRetrievingError, AuthorizationToken] = {
      for {
        tokenHeader <- singleHeaderOf(config.headerName).toRight(MissingHeader)
        authorizationToken <- AuthorizationToken.from(tokenHeader.value).toRight(InvalidValue)
        _ <- config.allowedPrefix match {
          case AllowedPrefix.Any                                                             => Right(())
          case AllowedPrefix.StrictlyDefined(prefix) if prefix === authorizationToken.prefix => Right(())
          case AllowedPrefix.StrictlyDefined(_)                                              => Left(InvalidValue)
        }
      } yield authorizationToken

    }

    def rorKbnLicenseType(
        implicit id: RequestId
    ): Option[RorKbnLicenseType] = {
      singleHeaderOf(Header.Name.rorKbnLicenseType)
        .flatMap(h => RorKbnLicenseType.from(h.value.value).toOption)
    }

    private def singleHeaderOf(name: Header.Name)(
        implicit id: RequestId
    ): Option[Header] = {
      Header.singleHeaderOrNone(name, in = restRequest.allHeaders)
    }

  }

}

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
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix.StrictlyDefined
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenPrefix.bearer
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.Header.{AmbiguousHeader, findSingleHeader}
import tech.beshu.ror.accesscontrol.request.RequestContext.AuthorizationTokenRetrievingError
import tech.beshu.ror.utils.uniquelist.UniqueList

import java.util.concurrent.ConcurrentHashMap

final class RequestHeaders(val all: UniqueList[Header]) {

  private given RequestId = RequestId(correlationId.value.value.value)

  private val singleHeaders = new ConcurrentHashMap[Header.Name, Either[AmbiguousHeader, Option[Header]]]()

  def singleOrAmbiguity(name: Header.Name): Either[AmbiguousHeader, Option[Header]] =
    singleHeaders.computeIfAbsent(name, _ => Header.singleHeaderOrAmbiguity(name, in = all))

  def single(name: Header.Name): Option[Header] =
    singleOrAmbiguity(name).toOption.flatten

  lazy val basicAuth: Option[BasicAuth] =
    single(Header.Name.authorization).flatMap(h => BasicAuth.parse(h.value))

  lazy val rawAuthHeader: Option[Header] =
    single(Header.Name.authorization)

  lazy val bearerToken: Either[AuthorizationTokenRetrievingError, AuthorizationToken] =
    authorizationTokenBy(
      AuthorizationTokenDef(headerName = Header.Name.authorization, allowedPrefix = StrictlyDefined(bearer))
    )

  def authorizationTokenBy(
      config: AuthorizationTokenDef
  ): Either[AuthorizationTokenRetrievingError, AuthorizationToken] = {
    for {
      tokenHeader <- singleOrAmbiguity(config.headerName).left
        .map(_ => AuthorizationTokenRetrievingError.AmbiguousHeader)
        .flatMap(_.toRight(AuthorizationTokenRetrievingError.MissingHeader))
      authorizationToken <- AuthorizationToken
        .from(tokenHeader.value)
        .toRight(AuthorizationTokenRetrievingError.InvalidValue)
      _ <- config.allowedPrefix match {
        case AllowedPrefix.Any                                                             => Right(())
        case AllowedPrefix.StrictlyDefined(prefix) if prefix === authorizationToken.prefix => Right(())
        case AllowedPrefix.StrictlyDefined(_) => Left(AuthorizationTokenRetrievingError.InvalidValue)
      }
    } yield authorizationToken
  }

  lazy val currentGroupId: Option[GroupId] =
    single(Header.Name.currentGroup).map(h => GroupId(h.value))

  lazy val impersonateAs: Option[User.Id] =
    single(Header.Name.impersonateAs).map(h => User.Id(h.value))

  lazy val rorKbnLicenseType: Option[RorKbnLicenseType] =
    single(Header.Name.rorKbnLicenseType).flatMap(h => RorKbnLicenseType.from(h.value.value).toOption)

  lazy val kibanaRequestPath: Option[NonEmptyString] =
    single(Header.Name.kibanaRequestPath).map(_.value)

  lazy val xApiKey: Option[NonEmptyString] =
    single(Header.Name.xApiKeyHeaderName).map(_.value)

  /** Returns the first address of the forwarded chain, and `None` when that address does not parse.
    * The client writes this address, so it is the client address only when a front proxy overwrites
    * the header. Two values of the header are one chain, not an ambiguity.
    */
  lazy val xForwardedFor: Option[Address] =
    all.view
      .find(_.name === Header.Name.xForwardedFor)
      .flatMap(_.value.value.split(",").headOption)
      .map(_.trim)
      .flatMap(Address.from)

  /** Two different values give a random ID, so that the client cannot pick one of them. */
  lazy val correlationId: Eval[CorrelationId] = Eval.later {
    findSingleHeader(Header.Name.correlationId, in = all) match {
      case Right(Some(header))   => CorrelationId(header.value)
      case Left(_) | Right(None) => CorrelationId.random
    }
  }

}

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

import java.net.HttpCookie
import java.nio.charset.StandardCharsets
import java.time.{Clock, Instant}

import cats.implicits._
import cats.{Eq, Show}
import com.google.common.hash.Hashing
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.parser._
import io.circe.{Decoder, Encoder}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.Header.Name.setCookie
import tech.beshu.ror.accesscontrol.domain.{Header, LoggedUser, User}
import tech.beshu.ror.accesscontrol.request.RorSessionCookie.ExtractingError.{Absent, Expired, Invalid}
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.providers.UuidProvider

import scala.jdk.CollectionConverters._
import scala.util.Try

final case class RorSessionCookie(userId: User.Id, expiryDate: Instant)

object RorSessionCookie extends Logging {
  private val rorCookieName = "ReadonlyREST_Session"

  sealed trait ExtractingError
  object ExtractingError {
    case object Absent extends ExtractingError
    case object Expired extends ExtractingError
    case object Invalid extends ExtractingError
  }

  def extractFrom(context: RequestContext,
                  user: LoggedUser)
                 (implicit clock: Clock,
                  uuidProvider: UuidProvider,
                  userIdEq: Eq[User.Id]): Either[ExtractingError, RorSessionCookie] = {
    for {
      httpCookie <- extractRorHttpCookie(context).toRight(Absent)
      cookieAndSignature <- parseRorSessionCookieAndSignature(httpCookie).left.map(_ => Invalid: ExtractingError)
      (cookie, signature) = cookieAndSignature
      _ <- checkCookie(cookie, signature, user)
    } yield cookie
  }

  def toSessionHeader(cookie: RorSessionCookie)
                     (implicit uuidProvider: UuidProvider): Header =
    new Header(
      setCookie,
      NonEmptyString.unsafeFrom(s"$rorCookieName=${coders.encoder((cookie, Signature.sign(cookie))).noSpaces}")
    )

  private def extractRorHttpCookie(context: RequestContext) = {
    context
      .headers
      .find(_.name === Header.Name.cookie)
      .flatMap(h => parseCookie(h.value.value))
      .flatMap(_.find(_.getName === rorCookieName))
  }

  private def parseRorSessionCookieAndSignature(httpCookie: HttpCookie) = {
    for {
      json <- parse(Option(httpCookie.getValue).getOrElse(""))
      decoded <- coders.decoder.decodeJson(json)
    } yield decoded
  }

  private def checkCookie(cookie: RorSessionCookie,
                          signature: Signature,
                          loggedUser: LoggedUser)
                         (implicit clock: Clock,
                          uuidProvider: UuidProvider,
                          userIdEq: Eq[User.Id]): Either[ExtractingError, Unit] = {
    val now = Instant.now(clock)
    if (cookie.userId =!= loggedUser.id) {
      logger.warn(s"this cookie does not belong to the user logged in as. Found in Cookie: ${cookie.userId.show} whilst in Authentication: ${loggedUser.id.show}")
      Left(Invalid)
    } else if (!signature.check(cookie)) {
      logger.warn(s"'${signature.value}' is not valid signature for ${cookie.show}")
      Left(Invalid)
    } else if (now.isAfter(cookie.expiryDate)) {
      logger.info(s"cookie was present but expired. Found: ${cookie.expiryDate}, now it's $now")
      Left(Expired)
    } else {
      Right({})
    }
  }

  private def parseCookie(value: String) = Try(HttpCookie.parse(value)).map(_.asScala.toList).toOption

  private object coders {
    implicit val encoder: Encoder[(RorSessionCookie, Signature)] = {
      implicit val userIdEncoder: Encoder[User.Id] = Encoder.encodeString.contramap(_.value.value)
      implicit val expiryDateEncoder: Encoder[Instant] = Encoder.encodeLong.contramap(_.toEpochMilli)
      implicit val signatureEncoder: Encoder[Signature] = Encoder.encodeString.contramap(_.value)
      implicit val cookieEncoder: Encoder[RorSessionCookie] = Encoder.forProduct2("user", "expire")(c => (c.userId, c.expiryDate))
      Encoder.encodeTuple2[RorSessionCookie, Signature]
    }
    implicit val decoder: Decoder[(RorSessionCookie, Signature)] = {
      implicit val userIdDecoder: Decoder[User.Id] = DecoderHelpers.decodeStringLikeNonEmpty.map(User.Id.apply)
      implicit val expiryDateDecoder: Decoder[Instant] = Decoder.decodeLong.map(Instant.ofEpochMilli)
      implicit val signatureDecoder: Decoder[Signature] = Decoder.decodeString.map(Signature.apply)
      implicit val cookieDecoder: Decoder[RorSessionCookie] = Decoder.forProduct2("user", "expire")(RorSessionCookie.apply)
      Decoder.decodeTuple2[RorSessionCookie, Signature]
    }
  }

  private final case class Signature(value: String) extends AnyVal {
    def check(cookie: RorSessionCookie)
             (implicit uuidProvider: UuidProvider): Boolean = Signature.sign(cookie) === this
  }

  private object Signature {
    def sign(cookie: RorSessionCookie)
            (implicit uuidProvider: UuidProvider): Signature = Signature {
      Hashing.sha256().hashString(
        s"${uuidProvider.instanceUuid.toString}${cookie.userId.value}${cookie.expiryDate.toEpochMilli}", StandardCharsets.UTF_8
      ).toString
    }

    implicit val eq: Eq[Signature] = Eq.fromUniversalEquals
  }

  private implicit val rorCookieShow: Show[RorSessionCookie] = Show.show(c => s"user: ${c.userId.value}, expiry: ${c.expiryDate.toEpochMilli}")
}
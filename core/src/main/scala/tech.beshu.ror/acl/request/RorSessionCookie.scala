package tech.beshu.ror.acl.request

import java.net.HttpCookie
import java.time.{Clock, Instant}

import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.show.logs._
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import io.circe.{Decoder, Encoder}
import io.circe.parser._
import tech.beshu.ror.acl.request.RorSessionCookie.ExtractingError.{Absent, Expired, Invalid}
import tech.beshu.ror.commons.aDomain.Header.Name.setCookie
import tech.beshu.ror.commons.domain.{LoggedUser, User}

import scala.util.Try
import scala.collection.JavaConverters._

final case class RorSessionCookie(userId: User.Id, expiryDate: Instant)

object RorSessionCookie extends StrictLogging {
  private val rorCookieName = "ReadonlyREST_Session"

  sealed trait ExtractingError
  object ExtractingError {
    case object Absent extends ExtractingError
    case object Expired extends ExtractingError
    case object Invalid extends ExtractingError
  }

  def extractFrom(context: RequestContext, user: LoggedUser)
                 (implicit clock: Clock): Either[ExtractingError, RorSessionCookie] = {
    for {
      httpCookie <- extractRorHttpCookie(context).toRight(Absent)
      rorCookie <- parseRorSessionCookie(httpCookie).left.map(_ => Invalid)
      _ <- checkCookie(rorCookie, user)
    } yield rorCookie
  }

  def toSessionHeader(cookie: RorSessionCookie): Header = setSessionHeader(s"$rorCookieName=${coders.encoder(cookie).noSpaces}")

  def emptySessionHeader: Header = setSessionHeader("")

  private def setSessionHeader(value: String) = new Header(setCookie, value)

  private def extractRorHttpCookie(context: RequestContext) = {
    context
      .headers
      .find(_.name === Header.Name.cookie)
      .flatMap(h => parseCookie(h.value))
      .flatMap(_.find(_.getName === rorCookieName))
  }

  private def parseRorSessionCookie(httpCookie: HttpCookie) = {
    for {
      json <- parse(Option(httpCookie.getValue).getOrElse(""))
      decoded <- coders.decoder.decodeJson(json)
    } yield decoded
  }

  private def checkCookie(cookie: RorSessionCookie, loggedUser: LoggedUser)
                         (implicit clock: Clock) = {
    val now = Instant.now(clock)
    if (cookie.userId =!= loggedUser.id) {
      logger.warn(s"this cookie does not belong to the user logged in as. Found in Cookie: ${cookie.userId.show} whilst in Authentication: ${loggedUser.id.show}")
      Left(Invalid)
    } else if (cookie.expiryDate.isAfter(now)) {
      logger.info(s"cookie was present but expired. Found: ${cookie.expiryDate}, now it's $now")
      Left(Expired)
    } else {
      Right({})
    }
  }

  private def parseCookie(value: String) = Try(HttpCookie.parse(value)).map(_.asScala.toList).toOption

  private object coders {
    // todo: implement
    implicit val encoder: Encoder[RorSessionCookie] = ???
    implicit val decoder: Decoder[RorSessionCookie] = ???
  }

}
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
package tech.beshu.ror.accesscontrol.domain

import cats.Show
import cats.data.Validated
import cats.implicits.*
import eu.timepit.refined.types.string.NonEmptyString
import io.lemonlabs.uri.Uri
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.constants
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RefinedUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

final class RorAuditIndexTemplate private(nameFormatter: DateTimeFormatter,
                                          rawPattern: String) {

  def indexName(instant: Instant): IndexName.Full = {
    IndexName.Full(NonEmptyString.unsafeFrom(nameFormatter.format(instant)))
  }

  def conforms(index: IndexName): Boolean = {
    index match {
      case IndexName.Full(name) =>
        Try(nameFormatter.parse(name.value)).isSuccess
      case IndexName.Pattern(_) =>
        IndexName
          .fromString(rawPattern)
          .exists { i =>
            PatternsMatcher
              .create(Set(index))
              .`match`(i)
          }
    }
  }
}
object RorAuditIndexTemplate {
  val default: RorAuditIndexTemplate = from(constants.AUDIT_LOG_DEFAULT_INDEX_TEMPLATE).toOption.get

  def apply(pattern: String): Either[CreationError, RorAuditIndexTemplate] = from(pattern)

  def from(pattern: String): Either[CreationError, RorAuditIndexTemplate] = {
    Try(DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.of("UTC"))) match {
      case Success(formatter) => Right(new RorAuditIndexTemplate(formatter, pattern.replaceAll("'", "")))
      case Failure(ex) => Left(CreationError.ParsingError(ex.getMessage))
    }
  }

  sealed trait CreationError
  object CreationError {
    final case class ParsingError(msg: String) extends CreationError
  }
}

final case class RorAuditDataStream private(dataStream: DataStreamName.Full)
object RorAuditDataStream {
  val default: RorAuditDataStream = RorAuditDataStream(DataStreamName.Full.fromNes(nes("readonlyrest_audit")))

  def apply(name: NonEmptyString): Either[CreationError, RorAuditDataStream] = from(name)

  def from(name: NonEmptyString): Either[CreationError, RorAuditDataStream] = {
    validateFormat(name)
      .map(DataStreamName.Full.fromNes)
      .map(RorAuditDataStream.apply)
      .leftWiden[CreationError]
  }

  private def validateFormat(value: NonEmptyString): Either[CreationError.FormatError, NonEmptyString] = {
    // Data stream names must meet the following criteria:
    // - Lowercase only
    // - Cannot include \, /, *, ?, ", <, >, |, ,, #, :, or a space character
    // - Cannot start with -, _, +, or .ds-
    // - Cannot be . or ..
    // - Cannot be longer than 255 bytes. Multi-byte characters count towards this limit faster.
    // https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-create-data-stream.html#indices-create-data-stream-api-path-params

    val forbiddenCharacters: List[Char] = List('\\', '/', '*', '?', '"', '<', '>', '|', ',', '#', ':', ' ')
    val forbiddenCharactersSet = forbiddenCharacters.toSet
    val forbiddenPrefixes = List("-", "_", "+", ".ds-")
    val forbiddenVariants = List(".", "..")
    val maxBytes = 255

    implicit val charShow: Show[Char] = Show.show(c => s"'$c'")
    implicit val stringShow: Show[String] = Show.show(str => s"'$str'")

    List[(String => Boolean, String)](
      (_.exists(c => c.isLetter && c.isUpper), "name must be lowercase"),
      (_.exists(forbiddenCharactersSet.contains), s"name must not contain forbidden characters ${forbiddenCharacters.show}"),
      (value => forbiddenPrefixes.exists(value.startsWith), s"name must not start with ${forbiddenPrefixes.show}"),
      (value => forbiddenVariants.contains(value), s"name cannot be any of ${forbiddenVariants.show}"),
      (value => value.getBytes.length > maxBytes, s"name must be not longer than 255 bytes"),
    )
      .map {
        case (test, errorMsg) => Validated.cond(!test(value.value), (), errorMsg).toValidatedNel
      }
      .sequence
      .toEither
      .leftMap {
        errors =>
          CreationError.FormatError(s"Data stream '${value.show}' has an invalid format. Cause: ${errors.toList.mkString(", ")}.")

      }.as(value)
  }

  sealed trait CreationError

  object CreationError {
    final case class FormatError(msg: String) extends CreationError
  }
}

sealed trait AuditCluster
object AuditCluster {
  case object LocalAuditCluster extends AuditCluster
  final case class RemoteAuditCluster(nodes: UniqueNonEmptyList[AuditClusterNode],
                                      mode: ClusterMode,
                                      credentials: Option[NodeCredentials]) extends AuditCluster

  final case class AuditClusterNode(uri: Uri) {
    def hostname: String = uri.toUrl.hostOption.map(_.value).getOrElse("localhost")

    def port: Int = uri.toUrl.port.getOrElse(9200)

    def scheme: String = uri.schemeOption.getOrElse("http")

    def credentials: Option[NodeCredentials] = {
      for {
        username <- uri.toUrl.user.flatMap(NonEmptyString.unapply)
        password <- uri.toUrl.password.flatMap(NonEmptyString.unapply)
      } yield NodeCredentials(username, password)
    }
  }

  final case class NodeCredentials(username: NonEmptyString, password: NonEmptyString)

  sealed trait ClusterMode
  object ClusterMode {
    case object RoundRobin extends ClusterMode
  }
}

final case class RorAuditLoggerName(value: NonEmptyString)
object RorAuditLoggerName {
  val default: RorAuditLoggerName = RorAuditLoggerName(nes("readonlyrest_audit"))
}
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
package tech.beshu.ror.accesscontrol

import java.nio.charset.StandardCharsets.UTF_8
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.{Base64, Locale, UUID}

import cats.Eq
import cats.data.NonEmptyList
import cats.implicits._
import cats.kernel.Monoid
import com.comcast.ip4s.{Cidr, Hostname, IpAddress}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.Claims
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.domain.Action._
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.{Local, Remote}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField.SpecificField
import tech.beshu.ror.accesscontrol.domain.Header.AuthorizationValueError.{EmptyAuthorizationValue, InvalidHeaderFormat, RorMetadataInvalidFormat}
import tech.beshu.ror.accesscontrol.header.ToHeaderValue
import tech.beshu.ror.accesscontrol.matchers.{IndicesNamesMatcher, MatcherWithWildcardsScalaAdapter, TemplateNamePatternMatcher, UniqueIdentifierGenerator}
import tech.beshu.ror.com.jayway.jsonpath.JsonPath
import tech.beshu.ror.utils.CaseMappingEquality
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.language.postfixOps
import scala.util.{Failure, Random, Success, Try}

object domain {

  final case class CorrelationId(value: NonEmptyString)
  object CorrelationId {
    def random: CorrelationId = new CorrelationId(NonEmptyString.unsafeFrom(UUID.randomUUID().toString))
  }

  abstract class Pattern[T](val value: NonEmptyString)

  sealed trait LoggedUser {
    def id: User.Id
  }
  object LoggedUser {
    final case class DirectlyLoggedUser(id: User.Id) extends LoggedUser
    final case class ImpersonatedUser(id: User.Id, impersonatedBy: User.Id) extends LoggedUser

    implicit val eqLoggedUser: Eq[DirectlyLoggedUser] = Eq.fromUniversalEquals
  }

  object User {
    final case class Id(value: NonEmptyString)
    object Id {
      type UserIdCaseMappingEquality = CaseMappingEquality[User.Id]
    }

    final case class UserIdPattern(override val value: NonEmptyString)
      extends Pattern[Id](value)
  }

  final case class UserIdPatterns(patterns: UniqueNonEmptyList[User.UserIdPattern])

  final case class Group(value: NonEmptyString)
  object Group {
    implicit val eq: Eq[Group] = Eq.by(_.value.value)
  }

  final case class Header(name: Header.Name, value: NonEmptyString)
  object Header {
    final case class Name(value: NonEmptyString)
    object Name {
      val xApiKeyHeaderName = Header.Name("X-Api-Key")
      val xForwardedFor = Name("X-Forwarded-For")
      val xForwardedUser = Name("X-Forwarded-User")
      val xUserOrigin = Name(Constants.HEADER_USER_ORIGIN)
      val kibanaHiddenApps = Name(Constants.HEADER_KIBANA_HIDDEN_APPS)
      val cookie = Name("Cookie")
      val setCookie = Name("Set-Cookie")
      val transientFields = Name(Constants.FIELDS_TRANSIENT)
      val currentGroup = Name(Constants.HEADER_GROUP_CURRENT)
      val availableGroups = Name(Constants.HEADER_GROUPS_AVAILABLE)
      val userAgent = Name("User-Agent")
      val authorization = Name("Authorization")
      val rorUser = Name(Constants.HEADER_USER_ROR)
      val kibanaAccess = Name(Constants.HEADER_KIBANA_ACCESS)
      val impersonateAs = Name("impersonate_as")
      val correlationId = Name(Constants.HEADER_CORRELATION_ID)

      implicit val eqName: Eq[Name] = Eq.by(_.value.value.toLowerCase(Locale.US))
    }

    def apply(name: Name, value: NonEmptyString): Header = new Header(name, value)

    def apply[T](name: Name, value: T)
                (implicit ev: ToHeaderValue[T]): Header = new Header(name, ev.toRawValue(value))

    def apply(nameAndValue: (NonEmptyString, NonEmptyString)): Header = new Header(Name(nameAndValue._1), nameAndValue._2)

    def fromAuthorizationValue(value: NonEmptyString): Either[AuthorizationValueError, NonEmptyList[Header]] = {
      value.value.splitBy("ror_metadata=") match {
        case (_, None) =>
          Right(NonEmptyList.one(new Header(Name.authorization, value)))
        case (basicAuthStr, Some(rorMetadata)) =>
          for {
            authorizationHeader <- createHeaderFromAuthorizationString(basicAuthStr)
            headersStr <- parseRorMetadataString(rorMetadata)
            headers <- headersStr.map(headerFrom).traverse(identity)
          } yield NonEmptyList.of(authorizationHeader, headers: _*)
      }
    }

    private def createHeaderFromAuthorizationString(authStr: String) = {
      val trimmed = authStr.trim
      val sanitized = if (trimmed.endsWith(",")) trimmed.substring(0, trimmed.length - 1) else trimmed
      NonEmptyString
        .from(sanitized)
        .map(new Header(Name.authorization, _))
        .left.map(_ => EmptyAuthorizationValue)
    }

    private def parseRorMetadataString(rorMetadataString: String) = {
      rorMetadataString.decodeBase64 match {
        case Some(value) =>
          Try(ujson.read(value).obj("headers").arr.toList.map(_.str))
            .toEither.left.map(_ => RorMetadataInvalidFormat(rorMetadataString, "Parsing JSON failed"))
        case None =>
          Left(RorMetadataInvalidFormat(rorMetadataString, "Decoding Base64 failed"))
      }

    }

    private def headerFrom(value: String) = {
      import tech.beshu.ror.utils.StringWiseSplitter._
      value
        .toNonEmptyStringsTuple
        .bimap(
          { case Error.CannotSplitUsingColon | Error.TupleMemberCannotBeEmpty => InvalidHeaderFormat(value) },
          { case (nonEmptyName, nonEmptyValue) => new Header(Name(nonEmptyName), nonEmptyValue) }
        )
    }

    sealed trait AuthorizationValueError
    object AuthorizationValueError {
      case object EmptyAuthorizationValue extends AuthorizationValueError
      final case class InvalidHeaderFormat(value: String) extends AuthorizationValueError
      final case class RorMetadataInvalidFormat(value: String, message: String) extends AuthorizationValueError
    }

    implicit val eqHeader: Eq[Header] = Eq.by(header => (header.name, header.value.value))
  }

  sealed trait AccessRequirement[T] {
    def value: T
  }
  object AccessRequirement {
    final case class MustBePresent[T](override val value: T) extends AccessRequirement[T]
    final case class MustBeAbsent[T](override val value: T) extends AccessRequirement[T]
  }

  final case class Credentials(user: User.Id, secret: PlainTextSecret)
  object Credentials {
    implicit def eqCredentials(implicit eq: Eq[User.Id]): Eq[Credentials] =
      Eq.and(Eq.by(_.user), Eq.by(_.secret))
  }
  final case class BasicAuth private(credentials: Credentials) {
    def header: Header = new Header(
      Header.Name.authorization,
      NonEmptyString.unsafeFrom(s"Basic ${Base64.getEncoder.encodeToString(s"${credentials.user.value}:${credentials.secret.value}".getBytes(UTF_8))}")
    )
  }
  object BasicAuth extends Logging {
    def fromHeader(header: Header): Option[BasicAuth] = {
      header.name match {
        case name if name === Header.Name.authorization => parse(header.value)
        case _ => None
      }
    }

    private def parse(headerValue: NonEmptyString) = {
      val authMethodName = "Basic "
      val rawValue = headerValue.value
      if (rawValue.startsWith(authMethodName) && rawValue.length > authMethodName.length) {
        val basicAuth = fromBase64(rawValue.substring(authMethodName.length))
        basicAuth match {
          case None =>
            logger.warn(s"Cannot decode value '$headerValue' to Basic Auth")
          case Some(_) =>
        }
        basicAuth
      } else {
        None
      }
    }

    private def fromBase64(base64Value: String) = {
      import tech.beshu.ror.utils.StringWiseSplitter._
      base64Value
        .decodeBase64
        .flatMap(_.toNonEmptyStringsTuple.toOption)
        .map { case (first, second) =>
          BasicAuth(Credentials(User.Id(first), PlainTextSecret(second)))
        }
    }
  }

  sealed trait Address
  object Address {
    final case class Ip(value: Cidr[IpAddress]) extends Address {
      def contains(ip: Ip): Boolean = value.contains(ip.value.address)
    }
    final case class Name(value: Hostname) extends Address

    def from(value: String): Option[Address] = {
      parseCidr(value) orElse
        parseIpAddress(value) orElse
        parseHostname(value)
    }

    private def parseCidr(value: String) =
      Cidr.fromString(value).map(Address.Ip.apply)

    private def parseHostname(value: String) =
      Hostname(value).map(Address.Name.apply)

    private def parseIpAddress(value: String) =
      (cutOffZoneIndex _ andThen IpAddress.apply andThen (_.map(createAddressIp))) (value)

    private def createAddressIp(ip: IpAddress) =
      Address.Ip(Cidr(ip, 32))

    private val ipv6WithLiteralScope = raw"""(?i)^(fe80:[a-z0-9:]+)%.*$$""".r

    private def cutOffZoneIndex(value: String): String = { //https://en.wikipedia.org/wiki/IPv6_address#Scoped_literal_IPv6_addresses
      value match {
        case ipv6WithLiteralScope(ipv6) => ipv6
        case noLiteralIp => noLiteralIp
      }
    }
  }

  final case class Action(value: String) extends AnyVal {
    def hasPrefix(prefix: String): Boolean = value.startsWith(prefix)

    def isSnapshot: Boolean = value.contains("/snapshot/")

    def isRepository: Boolean = value.contains("/repository/")

    def isTemplate: Boolean = value.contains("/template/")

    def isPutTemplate: Boolean = List(
      putTemplateAction,
      putIndexTemplateAction
    ).contains(this)

    def isRorAction: Boolean = List(
      rorUserMetadataAction,
      rorConfigAction,
      rorAuditEventAction,
      rorOldConfigAction
    ).contains(this)

    def isSearchAction: Boolean = List(
      searchAction,
      mSearchAction,
      fieldCapsAction,
      asyncSearchAction,
      rollupSearchAction,
      searchTemplateAction
    ).contains(this)
  }
  object Action {
    val searchAction = Action("indices:data/read/search")
    val mSearchAction = Action("indices:data/read/msearch")
    val restoreSnapshotAction = Action("cluster:admin/snapshot/restore")
    val fieldCapsAction = Action("indices:data/read/field_caps")
    val asyncSearchAction = Action("indices:data/read/async_search/submit")
    val rollupSearchAction = Action("indices:data/read/xpack/rollup/search")
    val searchTemplateAction = Action("indices:data/read/search/template")
    val putTemplateAction = Action("indices:admin/template/put")
    val putIndexTemplateAction = Action("indices:admin/index_template/put")
    // ROR actions
    val rorUserMetadataAction = Action("cluster:ror/user_metadata/get")
    val rorConfigAction = Action("cluster:ror/config/manage")
    val rorAuthMockAction = Action("cluster:ror/authmock/manage")
    val rorAuditEventAction = Action("cluster:ror/audit_event/put")
    val rorOldConfigAction = Action("cluster:ror/config/refreshsettings")

    implicit val eqAction: Eq[Action] = Eq.fromUniversalEquals
    implicit val caseMappingEqualityAction: CaseMappingEquality[Action] = CaseMappingEquality.instance(_.value, identity)
  }

  sealed trait IndexName
  object IndexName {

    val wildcard: IndexName.Wildcard = IndexName.Wildcard("*")
    val all: IndexName = IndexName.Full("_all")

    final case class Full(name: NonEmptyString)
      extends IndexName
    object Full {
      def fromString(value: String): Option[Full] =
        NonEmptyString.unapply(value).map(Full.apply)
    }

    final case class Wildcard private(name: NonEmptyString)
      extends IndexName
    object Wildcard {
      def fromString(value: String): Option[Wildcard] =
        NonEmptyString
          .unapply(value)
          .flatMap {
            case str if str.contains("*") => Some(Wildcard(str))
            case _ => None
          }
    }

    def fromString(value: String): Option[IndexName] =
      IndexName.Wildcard.fromString(value) orElse IndexName.Full.fromString(value)

    implicit val caseMappingEqualityIndexName: CaseMappingEquality[IndexName] =
      CaseMappingEquality.instance(
        {
          case IndexName.Full(name) => name
          case IndexName.Wildcard(name) => name
        },
        identity
      )
  }

  sealed trait ClusterIndexName
  object ClusterIndexName {

    final case class Local private(value: IndexName) extends ClusterIndexName
    object Local {

      val wildcard: ClusterIndexName.Local = Local(IndexName.wildcard)
      val devNullKibana: ClusterIndexName.Local = Local(IndexName.Full(".kibana-devnull"))
      val kibana: ClusterIndexName.Local = Local(IndexName.Full(".kibana"))

      def fromString(value: String): Option[ClusterIndexName.Local] = {
        IndexName
          .fromString(value)
          .map {
            case IndexName.all => IndexName.wildcard
            case index => index
          }
          .map(Local.apply)
      }

      def randomNonexistentIndex(prefix: String = ""): ClusterIndexName.Local = fromString {
        val nonexistentIndex = s"${NonEmptyString.unapply(prefix).map(i => s"${i}_").getOrElse("")}ROR_${Random.alphanumeric.take(10).mkString("")}"
        if (prefix.contains("*")) s"$nonexistentIndex*"
        else nonexistentIndex
      } get

      implicit val caseMappingEqualityIndexName: CaseMappingEquality[ClusterIndexName.Local] =
        CaseMappingEquality.instance(_.stringify, identity)
    }

    final case class Remote(value: IndexName, cluster: ClusterName) extends ClusterIndexName
    object Remote {
      sealed trait ClusterName
      object ClusterName {
        final case class Full private(value: NonEmptyString) extends ClusterName
        object Full {
          def fromString(value: String): Option[Full] =
            NonEmptyString.unapply(value).map(Full.apply)
        }

        final case class Wildcard private(value: NonEmptyString) extends ClusterName
        object Wildcard {
          def fromString(value: String): Option[Wildcard] =
            NonEmptyString.unapply(value).flatMap {
              case str if str.contains("*") => Some(ClusterName.Wildcard(str))
              case _ => None
            }
        }

        def fromString(value: String): Option[ClusterName] = {
          Wildcard.fromString(value) orElse Full.fromString(value)
        }

        val wildcard: ClusterName.Wildcard = Wildcard("*")

        implicit val caseMappingEqualityAction: CaseMappingEquality[ClusterName] =
          CaseMappingEquality.instance(_.stringify, identity)

        implicit class Stringify(val clusterName: ClusterName) extends AnyVal {
          def stringify: String = clusterName match {
            case Full(value) => value
            case Wildcard(value) => value
          }
        }
      }

      def fromString(value: String): Option[ClusterIndexName.Remote] = {
        value.splitBy(":") match {
          case (_, None) =>
            None
          case (clusterPart, Some(indexNamePart)) =>
            for {
              cluster <- ClusterName.fromString(clusterPart)
              indexName <- IndexName.fromString(indexNamePart)
            } yield Remote(indexName, cluster)
        }

      }

      implicit val caseMappingEqualityIndexName: CaseMappingEquality[ClusterIndexName.Remote] =
        CaseMappingEquality.instance(_.stringify, identity)
    }

    def fromString(value: String): Option[ClusterIndexName] = {
      Remote.fromString(value) orElse Local.fromString(value)
    }

    def unsafeFromString(value: String): ClusterIndexName =
      fromString(value).getOrElse(throw new IllegalStateException(s"Cannot create an index name from '$value'"))

    implicit val caseMappingEqualityIndexName: CaseMappingEquality[ClusterIndexName] =
      CaseMappingEquality.instance(_.stringify, identity)

    implicit val eqIndexName: Eq[ClusterIndexName] = Eq.fromUniversalEquals

    implicit class IndexMatch(indexName: ClusterIndexName) {
      private lazy val matcher = MatcherWithWildcardsScalaAdapter.create(indexName :: Nil)

      def matches(otherIndexName: ClusterIndexName): Boolean = indexName match {
        case Local(IndexName.Full(_)) => indexName == otherIndexName
        case Remote(IndexName.Full(_), _) => indexName == otherIndexName
        case Local(IndexName.Wildcard(_)) => matcher.`match`(otherIndexName)
        case Remote(IndexName.Wildcard(_), _) => matcher.`match`(otherIndexName)
      }
    }

    implicit class RandomNonexistentIndex(val base: ClusterIndexName) extends AnyVal {
      def randomNonexistentIndex(): ClusterIndexName = base match {
        case Local(IndexName.Full(name)) =>
          Local.randomNonexistentIndex(name)
        case Local(IndexName.Wildcard(name)) =>
          Local.randomNonexistentIndex(name)
        case Remote(IndexName.Full(name), clusterName) =>
          Local.randomNonexistentIndex(s"${clusterName.stringify}_$name")
        case Remote(IndexName.Wildcard(name), clusterName) =>
          Local.randomNonexistentIndex(s"${clusterName.stringify}_$name")
      }
    }

    implicit class Stringify(val indexName: ClusterIndexName) extends AnyVal {
      def stringify: String = indexName match {
        case Local(IndexName.Full(name)) => name
        case Local(IndexName.Wildcard(name)) => name
        case Remote(IndexName.Full(name), cluster) => s"${cluster.stringify}:$name"
        case Remote(IndexName.Wildcard(name), cluster) => s"${cluster.stringify}:$name"
      }

      def nonEmptyStringify: NonEmptyString = indexName match {
        case Local(IndexName.Full(name)) => name
        case Local(IndexName.Wildcard(name)) => name
        case Remote(IndexName.Full(name), cluster) => NonEmptyString.unsafeFrom(s"${cluster.stringify}:$name")
        case Remote(IndexName.Wildcard(name), cluster)=> NonEmptyString.unsafeFrom(s"${cluster.stringify}:$name")
      }
    }

    implicit class OnlyIndexName(val remoteIndexName: ClusterIndexName.Remote) extends AnyVal {
      def onlyIndexName: NonEmptyString = remoteIndexName match {
        case Remote(IndexName.Full(name), _) => name
        case Remote(IndexName.Wildcard(name), _) => name
      }
    }

    implicit class HasPrefix(val indexName: ClusterIndexName) extends AnyVal {
      def hasPrefix(prefix: String): Boolean = {
        indexName match {
          case local: Local => local.stringify.startsWith(prefix)
          case remote: Remote => remote.onlyIndexName.startsWith(prefix)
        }
      }
    }

    implicit class IsAllowedBy(val indexName: ClusterIndexName) extends AnyVal {
      def isAllowedBy(allowedIndices: Traversable[ClusterIndexName]): Boolean = {
        indexName match {
          case Placeholder(placeholder) =>
            val potentialAliases = allowedIndices.map(i => placeholder.index(i.nonEmptyStringify))
            potentialAliases.exists { alias => allowedIndices.exists(_.matches(alias)) }
          case _ =>
            allowedIndices.exists(_.matches(indexName))
        }
      }
    }

    implicit class HasWildcard(val indexName: ClusterIndexName) extends AnyVal {
      def hasWildcard: Boolean = indexName match {
        case Local(IndexName.Full(_)) => false
        case Local(IndexName.Wildcard(_)) => true
        case Remote(IndexName.Full(_), _) => false
        case Remote(IndexName.Wildcard(_), _) => true
      }
    }

    implicit class AllIndicesRequested(val indexName: ClusterIndexName) extends AnyVal {
      def allIndicesRequested: Boolean = indexName match {
        case Local(IndexName.wildcard | IndexName.all) => true
        case Local(IndexName.Full(_) | IndexName.Wildcard(_)) => false
        case Remote(IndexName.wildcard | IndexName.all, ClusterName.wildcard) => true
        case Remote(IndexName.Full(_) | IndexName.Wildcard(_), _) => false
      }
    }
  }

  final case class IndexPattern(value: ClusterIndexName) {
    private lazy val matcher = MatcherWithWildcardsScalaAdapter.create(value :: Nil)

    def isAllowedBy(index: ClusterIndexName): Boolean = {
      matcher.`match`(index) || index.matches(value)
    }

    def isAllowedByAny(anyIndexFrom: Traversable[ClusterIndexName]): Boolean = {
      anyIndexFrom.exists(this.isAllowedBy)
    }

    def isSubsetOf(index: ClusterIndexName): Boolean = {
      index.matches(value)
    }
  }
  object IndexPattern {

    def fromString(value: String): Option[IndexPattern] =
      ClusterIndexName.fromString(value).map(IndexPattern.apply)
  }

  final case class AliasPlaceholder private(alias: ClusterIndexName) extends AnyVal {
    def index(value: NonEmptyString): ClusterIndexName = alias match {
      case Local(IndexName.Full(_)) =>
        val replaced = alias.stringify.replaceAll(AliasPlaceholder.escapedPlaceholder, value.value)
        Local(IndexName.Full(NonEmptyString.unsafeFrom(replaced)))
      case Local(IndexName.Wildcard(_)) =>
        val replaced = alias.stringify.replaceAll(AliasPlaceholder.escapedPlaceholder, value.value)
        Local(IndexName.Wildcard(NonEmptyString.unsafeFrom(replaced)))
      case i@Remote(IndexName.Full(_), clusterName) =>
        val replaced = i.onlyIndexName.replaceAll(AliasPlaceholder.escapedPlaceholder, value.value)
        Remote(IndexName.Full(NonEmptyString.unsafeFrom(replaced)), clusterName)
      case i@Remote(IndexName.Wildcard(_), clusterName) =>
        val replaced = i.onlyIndexName.replaceAll(AliasPlaceholder.escapedPlaceholder, value.value)
        Remote(IndexName.Wildcard(NonEmptyString.unsafeFrom(replaced)), clusterName)
    }
  }
  object AliasPlaceholder {
    private val placeholder = "{index}"
    private val escapedPlaceholder = placeholder.replaceAllLiterally("{", "\\{").replaceAllLiterally("}", "\\}")

    def from(alias: ClusterIndexName): Option[AliasPlaceholder] = alias match {
      case Local(IndexName.Full(name)) if name.contains(placeholder) => Some(AliasPlaceholder(alias))
      case Local(IndexName.Full(_)) => None
      case Local(IndexName.Wildcard(name)) if name.contains(placeholder) => Some(AliasPlaceholder(alias))
      case Local(IndexName.Wildcard(_)) => None
      case Remote(IndexName.Full(name), _) if name.contains(placeholder) => Some(AliasPlaceholder(alias))
      case Remote(IndexName.Full(_), _) => None
      case Remote(IndexName.Wildcard(name), _) if name.contains(placeholder) => Some(AliasPlaceholder(alias))
      case Remote(IndexName.Wildcard(_), _) => None
    }
  }

  object Placeholder {
    def unapply(alias: ClusterIndexName): Option[AliasPlaceholder] = AliasPlaceholder.from(alias)
  }

  final case class FullLocalIndexWithAliases(indexName: IndexName.Full,
                                             aliasesNames: Set[IndexName.Full]) {
    lazy val index: ClusterIndexName.Local = ClusterIndexName.Local(indexName)
    lazy val aliases: Set[ClusterIndexName.Local] = aliasesNames.map(ClusterIndexName.Local.apply)
    lazy val all: Set[ClusterIndexName.Local] = aliases + index
  }

  final case class FullRemoteIndexWithAliases(clusterName: ClusterName.Full,
                                              indexName: IndexName.Full,
                                              aliasesNames: Set[IndexName.Full]) {
    lazy val index: ClusterIndexName.Remote = ClusterIndexName.Remote(indexName, clusterName)
    lazy val aliases: Set[ClusterIndexName.Remote] = aliasesNames.map(ClusterIndexName.Remote(_, clusterName))
    lazy val all: Set[ClusterIndexName.Remote] = aliases + index
  }

  final case class RorConfigurationIndex(index: IndexName.Full) extends AnyVal

  final class RorAuditIndexTemplate private(nameFormatter: DateTimeFormatter,
                                            rawPattern: String) {

    def indexName(instant: Instant): IndexName.Full = {
      IndexName.Full(NonEmptyString.unsafeFrom(nameFormatter.format(instant)))
    }

    def conforms(index: IndexName): Boolean = {
      index match {
        case IndexName.Full(name) =>
          Try(nameFormatter.parse(name.value.value)).isSuccess
        case IndexName.Wildcard(_) =>
          IndexName
            .fromString(rawPattern)
            .exists { i =>
              IndicesNamesMatcher
                .create(Set(index))
                .`match`(i)
            }
      }
    }
  }
  object RorAuditIndexTemplate {
    val default: RorAuditIndexTemplate = from(Constants.AUDIT_LOG_DEFAULT_INDEX_TEMPLATE).right.get

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

  sealed trait RepositoryName
  object RepositoryName {
    final case class Full private(value: NonEmptyString) extends RepositoryName
    final case class Pattern private(value: NonEmptyString) extends RepositoryName
    case object All extends RepositoryName
    case object Wildcard extends RepositoryName

    def all: RepositoryName = All

    def wildcard: RepositoryName = Wildcard

    def from(value: String): Option[RepositoryName] = {
      NonEmptyString.unapply(value).map {
        case Refined("_all") => All
        case Refined("*") => Wildcard
        case v if v.contains("*") => Pattern(NonEmptyString.unsafeFrom(v))
        case v => Full(NonEmptyString.unsafeFrom(v))
      }
    }

    def toString(snapshotName: RepositoryName): String = snapshotName match {
      case Full(value) => value.value
      case Pattern(value) => value.value
      case All => "_all"
      case Wildcard => "*"
    }

    implicit val eqRepository: Eq[RepositoryName] = Eq.fromUniversalEquals
    implicit val caseMappingEqualityRepositoryName: CaseMappingEquality[RepositoryName] = CaseMappingEquality.instance(
      {
        case Full(value) => value.value
        case Pattern(value) => value.value
        case All => "*"
        case Wildcard => "*"
      },
      identity
    )
  }

  sealed trait SnapshotName
  object SnapshotName {
    final case class Full private(value: NonEmptyString) extends SnapshotName
    final case class Pattern private(value: NonEmptyString) extends SnapshotName
    case object All extends SnapshotName
    case object Wildcard extends SnapshotName

    def all: SnapshotName = SnapshotName.All

    def wildcard: SnapshotName = SnapshotName.Wildcard

    def from(value: String): Option[SnapshotName] = {
      NonEmptyString.unapply(value).map {
        case Refined("_all") => All
        case Refined("*") => Wildcard
        case v if v.contains("*") => Pattern(NonEmptyString.unsafeFrom(v))
        case v => Full(NonEmptyString.unsafeFrom(v))
      }
    }

    def toString(snapshotName: SnapshotName): String = snapshotName match {
      case Full(value) => value.value
      case Pattern(value) => value.value
      case All => "_all"
      case Wildcard => "*"
    }

    implicit val eqRepository: Eq[SnapshotName] = Eq.fromUniversalEquals
    implicit val caseMappingEqualitySnapshotName: CaseMappingEquality[SnapshotName] = CaseMappingEquality.instance(
      {
        case Full(value) => value.value
        case Pattern(value) => value.value
        case All => "*"
        case Wildcard => "*"
      },
      identity
    )
  }

  sealed trait Template {
    def name: TemplateName
  }
  object Template {
    final case class LegacyTemplate(override val name: TemplateName,
                                    patterns: UniqueNonEmptyList[IndexPattern],
                                    aliases: Set[ClusterIndexName])
      extends Template

    final case class IndexTemplate(override val name: TemplateName,
                                   patterns: UniqueNonEmptyList[IndexPattern],
                                   aliases: Set[ClusterIndexName])
      extends Template

    final case class ComponentTemplate(override val name: TemplateName,
                                       aliases: Set[ClusterIndexName])
      extends Template
  }

  sealed trait TemplateOperation
  object TemplateOperation {

    final case class GettingLegacyTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
      extends TemplateOperation

    final case class AddingLegacyTemplate(name: TemplateName,
                                          patterns: UniqueNonEmptyList[IndexPattern],
                                          aliases: Set[ClusterIndexName])
      extends TemplateOperation

    final case class DeletingLegacyTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
      extends TemplateOperation

    final case class GettingIndexTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
      extends TemplateOperation

    final case class AddingIndexTemplate(name: TemplateName,
                                         patterns: UniqueNonEmptyList[IndexPattern],
                                         aliases: Set[ClusterIndexName])
      extends TemplateOperation

    final case class AddingIndexTemplateAndGetAllowedOnes(name: TemplateName,
                                                          patterns: UniqueNonEmptyList[IndexPattern],
                                                          aliases: Set[ClusterIndexName],
                                                          allowedTemplates: List[TemplateNamePattern])
      extends TemplateOperation

    final case class DeletingIndexTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
      extends TemplateOperation

    final case class GettingLegacyAndIndexTemplates(gettingLegacyTemplates: GettingLegacyTemplates,
                                                    gettingIndexTemplates: GettingIndexTemplates)
      extends TemplateOperation

    final case class GettingComponentTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
      extends TemplateOperation

    final case class AddingComponentTemplate(name: TemplateName,
                                             aliases: Set[ClusterIndexName])
      extends TemplateOperation

    final case class DeletingComponentTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
      extends TemplateOperation
  }

  final case class TemplateName(value: NonEmptyString)
  object TemplateName {
    def fromString(value: String): Option[TemplateName] = {
      NonEmptyString.from(value).map(TemplateName.apply).toOption
    }

    implicit val eqTemplateName: Eq[TemplateName] = Eq.fromUniversalEquals
  }

  final case class TemplateNamePattern(value: NonEmptyString) {
    private lazy val matcher = TemplateNamePatternMatcher.create(Set(this))

    def matches(templateName: TemplateName): Boolean = matcher.`match`(templateName)

  }
  object TemplateNamePattern {
    implicit val caseMappingEqualityTemplateNamePattern: CaseMappingEquality[TemplateNamePattern] =
      CaseMappingEquality.instance(_.value.value, identity)
    val wildcard: TemplateNamePattern = TemplateNamePattern("*")

    def fromString(value: String): Option[TemplateNamePattern] = {
      NonEmptyString
        .from(value).toOption
        .map(TemplateNamePattern.apply)
    }

    def from(templateName: TemplateName): TemplateNamePattern = TemplateNamePattern(templateName.value)

    def generateNonExistentBasedOn(templateNamePattern: TemplateNamePattern)
                                  (implicit identifierGenerator: UniqueIdentifierGenerator): TemplateNamePattern = {
      val nonexistentTemplateNamePattern = s"${templateNamePattern.value}_ROR_${identifierGenerator.generate(10)}"
      TemplateNamePattern(NonEmptyString.unsafeFrom(nonexistentTemplateNamePattern))
    }

    def findMostGenericTemplateNamePatten(in: NonEmptyList[TemplateNamePattern]): TemplateNamePattern = {
      def allTheSame(letters: List[Char]) = {
        letters.size > 1 && letters.distinct.size == 1
      }
      def minTemplateNameLength() = {
        in.foldLeft(Int.MaxValue) {
          case (minLength, elem) if elem.value.length < minLength => elem.value.length
          case (minLength, _) => minLength
        }
      }

      if (in.size > 1) {
        TemplateNamePattern
          .fromString {
            val minLength = minTemplateNameLength()
            in
              .toList.map(_.value.value.substring(0, minLength).toCharArray)
              .transpose
              .foldLeft((false, StringBuilder.newBuilder)) {
                case ((false, builder), letters) if allTheSame(letters) =>
                  (false, builder.append(letters.head))
                case ((false, builder), _) =>
                  (true, builder.append("*"))
                case (acc, _) =>
                  acc
              }
              ._2
              .toString
          }
          .getOrElse(throw new IllegalMonitorStateException(s"Cannot find the most generic template name patten in ${in.toList.map(_.show).mkString(",")}"))
      } else {
        in.head
      }
    }

    implicit val eqTemplateName: Eq[TemplateNamePattern] = Eq.fromUniversalEquals
  }

  final case class ApiKey(value: NonEmptyString)
  object ApiKey {
    implicit val eqApiKey: Eq[ApiKey] = Eq.fromUniversalEquals
  }

  final case class PlainTextSecret(value: NonEmptyString)
  object PlainTextSecret {
    implicit val eqAuthKey: Eq[PlainTextSecret] = Eq.fromUniversalEquals
  }

  final case class KibanaApp(value: NonEmptyString)
  object KibanaApp {
    implicit val eqKibanaApps: Eq[KibanaApp] = Eq.fromUniversalEquals
  }

  final case class UserOrigin(value: NonEmptyString)

  final case class Type(value: String) extends AnyVal

  final case class Filter(value: NonEmptyString)

  sealed trait KibanaAccess
  object KibanaAccess {
    case object RO extends KibanaAccess
    case object RW extends KibanaAccess
    case object ROStrict extends KibanaAccess
    case object Admin extends KibanaAccess
    case object Unrestricted extends KibanaAccess

    implicit val eqKibanaAccess: Eq[KibanaAccess] = Eq.fromUniversalEquals
  }

  final case class UriPath private(value: NonEmptyString) {
    def isAuditEventPath: Boolean =
      this != UriPath.slashPath && UriPath.auditEventPath.value.value.startsWith(value.value)

    def isCurrentUserMetadataPath: Boolean =
      this != UriPath.slashPath && UriPath.currentUserMetadataPath.value.value.startsWith(value.value)

    def isCatTemplatePath: Boolean = value.value.startsWith("/_cat/templates")

    def isTemplatePath: Boolean = value.value.startsWith("/_template")

    def isCatIndicesPath: Boolean = value.value.startsWith("/_cat/indices")

    def isAliasesPath: Boolean =
      value.value.startsWith("/_cat/aliases") ||
        value.value.startsWith("/_alias") ||
        "^/(\\w|\\*)*/_alias(|/)$".r.findFirstMatchIn(value.value).isDefined ||
        "^/(\\w|\\*)*/_alias/(\\w|\\*)*(|/)$".r.findFirstMatchIn(value.value).isDefined
  }
  object UriPath {
    val currentUserMetadataPath = UriPath(NonEmptyString.unsafeFrom(Constants.CURRENT_USER_METADATA_PATH))
    val auditEventPath = UriPath(NonEmptyString.unsafeFrom(Constants.AUDIT_EVENT_COLLECTOR_PATH))
    val slashPath = UriPath("/")

    implicit val eqUriPath: Eq[UriPath] = Eq.fromUniversalEquals

    def from(value: String): Option[UriPath] = {
      NonEmptyString
        .from(value).toOption
        .map(UriPath.from)
    }

    def from(value: NonEmptyString): UriPath = {
      if(value.startsWith("/")) new UriPath(value)
      else new UriPath(NonEmptyString.unsafeFrom(s"/$value"))
    }

    object CatTemplatePath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if (uriPath.isCatTemplatePath) Some(uriPath)
        else None
      }
    }

    object CatIndicesPath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if (uriPath.isCatIndicesPath) Some(uriPath)
        else None
      }
    }

    object TemplatePath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if (uriPath.isTemplatePath) Some(uriPath)
        else None
      }
    }

    object AliasesPath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if (uriPath.isAliasesPath) Some(uriPath)
        else None
      }
    }

    object CurrentUserMetadataPath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if (uriPath.isCurrentUserMetadataPath) Some(uriPath)
        else None
      }
    }
  }

  final case class ClaimName(name: JsonPath) {

    override def equals(other: Any): Boolean = {
      other match {
        case that: ClaimName => that.name.getPath.equals(this.name.getPath)
        case _ => false
      }
    }

    override def hashCode: Int = name.getPath.hashCode
  }

  final case class JwtToken(value: NonEmptyString)

  final case class JwtTokenPayload(claims: Claims)

  final case class AuthorizationTokenDef(headerName: Header.Name, prefix: String)

  final case class AuthorizationToken(value: NonEmptyString)

  final case class DocumentId(value: String) extends AnyVal

  final case class DocumentWithIndex(index: ClusterIndexName, documentId: DocumentId)

  object ResponseFieldsFiltering {

    final case class ResponseFieldsRestrictions(responseFields: UniqueNonEmptyList[ResponseField],
                                                mode: AccessMode)

    final case class ResponseField(value: NonEmptyString)

    sealed trait AccessMode
    object AccessMode {
      case object Whitelist extends AccessMode
      case object Blacklist extends AccessMode
    }
  }

  sealed trait DocumentAccessibility
  object DocumentAccessibility {
    case object Accessible extends DocumentAccessibility
    case object Inaccessible extends DocumentAccessibility
  }

  final case class FieldLevelSecurity(restrictions: FieldLevelSecurity.FieldsRestrictions,
                                      strategy: FieldLevelSecurity.Strategy)

  object FieldLevelSecurity {

    final case class FieldsRestrictions(documentFields: UniqueNonEmptyList[DocumentField],
                                        mode: AccessMode)

    object FieldsRestrictions {
      final case class DocumentField(value: NonEmptyString)

      sealed trait AccessMode
      object AccessMode {
        case object Whitelist extends AccessMode
        case object Blacklist extends AccessMode
      }
    }

    sealed trait Strategy
    object Strategy {
      case object FlsAtLuceneLevelApproach extends Strategy
      sealed trait BasedOnBlockContextOnly extends Strategy

      object BasedOnBlockContextOnly {
        case object EverythingAllowed extends BasedOnBlockContextOnly
        final case class NotAllowedFieldsUsed(fields: NonEmptyList[SpecificField]) extends BasedOnBlockContextOnly
      }
    }

    sealed trait RequestFieldsUsage
    object RequestFieldsUsage {

      case object CannotExtractFields extends RequestFieldsUsage
      case object NotUsingFields extends RequestFieldsUsage
      final case class UsingFields(usedFields: NonEmptyList[UsedField]) extends RequestFieldsUsage

      sealed trait UsedField {
        def value: String
      }

      object UsedField {

        final case class SpecificField private(value: String) extends UsedField

        object SpecificField {
          implicit class Ops(val specificField: SpecificField) extends AnyVal {
            def obfuscate: ObfuscatedRandomField = ObfuscatedRandomField(specificField)
          }
        }

        final case class FieldWithWildcard private(value: String) extends UsedField

        def apply(value: String): UsedField = {
          if (hasWildcard(value))
            FieldWithWildcard(value)
          else
            SpecificField(value)
        }

        private def hasWildcard(fieldName: String): Boolean = fieldName.contains("*")
      }

      final case class ObfuscatedRandomField(value: String) extends AnyVal
      object ObfuscatedRandomField {
        def apply(from: SpecificField): ObfuscatedRandomField = {
          new ObfuscatedRandomField(s"${from.value}_ROR_${Random.alphanumeric.take(10).mkString("")}")
        }
      }

      implicit val monoidInstance: Monoid[RequestFieldsUsage] = Monoid.instance(NotUsingFields, {
        case (CannotExtractFields, _) => CannotExtractFields
        case (_, CannotExtractFields) => CannotExtractFields
        case (other, NotUsingFields) => other
        case (NotUsingFields, other) => other
        case (UsingFields(firstFields), UsingFields(secondFields)) => UsingFields(firstFields ::: secondFields)
      })
    }
  }
}

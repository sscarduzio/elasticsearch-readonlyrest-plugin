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
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.Claims
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.blocks.rules.utils.{IndicesMatcher, MatcherWithWildcardsScalaAdapter, UniqueIdentifierGenerator}
import tech.beshu.ror.accesscontrol.domain.Action.{asyncSearchAction, fieldCapsAction, mSearchAction, rollupSearchAction, rorAuditEventAction, rorConfigAction, rorOldConfigAction, rorUserMetadataAction, searchAction, searchTemplateAction, _}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField.SpecificField
import tech.beshu.ror.accesscontrol.domain.Header.AuthorizationValueError.{EmptyAuthorizationValue, InvalidHeaderFormat, RorMetadataInvalidFormat}
import tech.beshu.ror.accesscontrol.header.ToHeaderValue
import tech.beshu.ror.com.jayway.jsonpath.JsonPath
import tech.beshu.ror.utils.CaseMappingEquality
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.util.{Failure, Random, Success, Try}

object domain {

  final case class CorrelationId(value: NonEmptyString)
  object CorrelationId {
    def random: CorrelationId = new CorrelationId(NonEmptyString.unsafeFrom(UUID.randomUUID().toString))
  }

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
  }

  final case class Group(value: NonEmptyString)

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
      val sanitized = if(trimmed.endsWith(",")) trimmed.substring(0, trimmed.length - 1) else trimmed
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
    implicit def eqCredentials(implicit userIdEq: Eq[User.Id]): Eq[Credentials] =
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
      if(rawValue.startsWith(authMethodName) && rawValue.length > authMethodName.length) {
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
    val rorAuditEventAction = Action("cluster:ror/audit_event/put")
    val rorOldConfigAction = Action("cluster:ror/config/refreshsettings")

    implicit val eqAction: Eq[Action] = Eq.fromUniversalEquals
    implicit val caseMappingEqualityAction: CaseMappingEquality[Action] = CaseMappingEquality.instance(_.value, identity)
  }

  final case class IndexName(value: NonEmptyString) {
    private lazy val matcher = MatcherWithWildcardsScalaAdapter.create(this :: Nil)

    def isClusterIndex: Boolean = value.value.contains(":")
    def hasPrefix(prefix: String): Boolean = value.value.startsWith(prefix)
    def hasWildcard: Boolean = value.value.contains("*")
    def matches(indexName: IndexName): Boolean = {
      if(hasWildcard) matcher.`match`(indexName)
      else this == indexName
    }
    def isAllowedBy(allowedIndices: Traversable[IndexName]): Boolean = {
      this match {
        case Placeholder(placeholder) =>
          val potentialAliases = allowedIndices.map(i => placeholder.index(i.value))
          potentialAliases.exists { alias => allowedIndices.exists(_.matches(alias)) }
        case _ =>
          allowedIndices.exists(_.matches(this))
      }
    }
  }
  object IndexName {
    implicit val caseMappingEqualityIndexName: CaseMappingEquality[IndexName] = CaseMappingEquality.instance(_.value.value, identity)

    val wildcard: IndexName = fromUnsafeString("*")
    val all: IndexName = fromUnsafeString("_all")
    val devNullKibana: IndexName = fromUnsafeString(".kibana-devnull")
    val kibana: IndexName = fromUnsafeString(".kibana")

    implicit val eqIndexName: Eq[IndexName] = Eq.fromUniversalEquals

    def fromString(value: String): Option[IndexName] = NonEmptyString.from(value).map(from).toOption

    def fromUnsafeString(value: String): IndexName = from(NonEmptyString.unsafeFrom(value))

    def randomNonexistentIndex(prefix: String = ""): IndexName = from {
      NonEmptyString.unsafeFrom {
        val nonexistentIndex = s"${NonEmptyString.unapply(prefix).map(i => s"${i}_").getOrElse("")}ROR_${Random.alphanumeric.take(10).mkString("")}"
        if(prefix.contains("*")) s"$nonexistentIndex*"
        else nonexistentIndex
      }
    }

    private def from(name: NonEmptyString) = {
      IndexName(name) match {
        case index if index == all => wildcard
        case index => index
      }
    }
  }

  final case class IndexPattern(value: NonEmptyString) {
    private lazy val matcher = MatcherWithWildcardsScalaAdapter.create(toIndexName :: Nil)

    lazy val toIndexName: IndexName = IndexName(value)

    def isAllowedBy(index: IndexName): Boolean = {
      matcher.`match`(index) || index.matches(toIndexName)
    }

    def isAllowedByAny(anyIndexFrom: Traversable[IndexName]): Boolean = {
      anyIndexFrom.exists(this.isAllowedBy)
    }

    def isSubsetOf(index: IndexName): Boolean = {
      index.matches(toIndexName)
    }
  }
  object IndexPattern {

    def fromString(value: String): Option[IndexPattern] = NonEmptyString.from(value).map(IndexPattern.apply).toOption
  }

  final case class AliasPlaceholder private (alias: IndexName) extends AnyVal {
    def index(value: NonEmptyString): IndexName =
      IndexName.fromUnsafeString(alias.value.replaceAll(AliasPlaceholder.escapedPlaceholder, value.value))
  }
  object AliasPlaceholder {
    private val placeholder = "{index}"
    private val escapedPlaceholder = placeholder.replaceAllLiterally("{", "\\{").replaceAllLiterally("}", "\\}")

    def from(alias: IndexName): Option[AliasPlaceholder] =
      if(alias.value.contains(placeholder)) Some(AliasPlaceholder(alias)) else None
  }

  object Placeholder {
    def unapply(alias: IndexName): Option[AliasPlaceholder] = AliasPlaceholder.from(alias)
  }

  final case class IndexWithAliases(index: IndexName, aliases: Set[IndexName]) {
    def all: Set[IndexName] = aliases + index
  }

  final case class RorConfigurationIndex(index: IndexName) extends AnyVal

  final class RorAuditIndexTemplate private(nameFormatter: DateTimeFormatter,
                                            rawPattern: String) {

    def indexName(instant: Instant): IndexName = {
      IndexName.fromUnsafeString(nameFormatter.format(instant))
    }

    def conforms(index: IndexName): Boolean = {
      if(index.hasWildcard) {
        IndicesMatcher
          .create(Set(index))
          .`match`(IndexName.fromUnsafeString(rawPattern))
      } else {
        Try(nameFormatter.parse(index.value.value)).isSuccess
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

  final case class RepositoryName(value: NonEmptyString)
  object RepositoryName {
    val all: RepositoryName = RepositoryName(NonEmptyString.unsafeFrom("_all"))
    val wildcard: RepositoryName = RepositoryName(NonEmptyString.unsafeFrom("*"))

    implicit val eqRepository: Eq[RepositoryName] = Eq.fromUniversalEquals
    implicit val caseMappingEqualityRepositoryName:CaseMappingEquality[RepositoryName] = CaseMappingEquality.instance(_.value.value, identity)
  }
  final case class SnapshotName(value: NonEmptyString)
  object SnapshotName {
    val all: SnapshotName = SnapshotName(NonEmptyString.unsafeFrom("_all"))
    val wildcard: SnapshotName = SnapshotName(NonEmptyString.unsafeFrom("*"))

    implicit val eqRepository: Eq[SnapshotName] = Eq.fromUniversalEquals
    implicit val caseMappingEqualitySnapshotName:CaseMappingEquality[SnapshotName] =
      CaseMappingEquality.instance(_.value.value, identity)
  }

  sealed trait Template {
    def name: TemplateName
  }
  object Template {
    final case class LegacyTemplate(override val name: TemplateName,
                                    patterns: UniqueNonEmptyList[IndexPattern],
                                    aliases: Set[IndexName])
      extends Template

    final case class IndexTemplate(override val name: TemplateName,
                                   patterns: UniqueNonEmptyList[IndexPattern],
                                   aliases: Set[IndexName])
      extends Template

    final case class ComponentTemplate(override val name: TemplateName,
                                       aliases: Set[IndexName])
      extends Template
  }

  sealed trait TemplateOperation
  object TemplateOperation {

    final case class GettingLegacyTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
      extends TemplateOperation

    final case class AddingLegacyTemplate(name: TemplateName,
                                          patterns: UniqueNonEmptyList[IndexPattern],
                                          aliases: Set[IndexName])
      extends TemplateOperation

    final case class DeletingLegacyTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
      extends TemplateOperation

    final case class GettingIndexTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
      extends TemplateOperation

    final case class AddingIndexTemplate(name: TemplateName,
                                         patterns: UniqueNonEmptyList[IndexPattern],
                                         aliases: Set[IndexName])
      extends TemplateOperation

    final case class AddingIndexTemplateAndGetAllowedOnes(name: TemplateName,
                                                          patterns: UniqueNonEmptyList[IndexPattern],
                                                          aliases: Set[IndexName],
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
                                             aliases: Set[IndexName])
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

  final case class TemplateNamePattern(value: NonEmptyString)
  object TemplateNamePattern {
    implicit val caseMappingEqualityTemplateNamePattern: CaseMappingEquality[TemplateNamePattern] = CaseMappingEquality.instance(_.value.value, identity)

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

  final case class UriPath(value: NonEmptyString) {
    def isAuditEventPath: Boolean = UriPath.auditEventPath.value.value.startsWith(value.value)
    def isCurrentUserMetadataPath: Boolean = UriPath.currentUserMetadataPath.value.value.startsWith(value.value)
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

    implicit val eqUriPath: Eq[UriPath] = Eq.fromUniversalEquals

    def from(value: String): Option[UriPath] = {
      NonEmptyString
        .from(value).toOption
        .map(UriPath.apply)
    }

    object CatTemplatePath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if(uriPath.isCatTemplatePath) Some(uriPath)
        else None
      }
    }

    object CatIndicesPath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if(uriPath.isCatIndicesPath) Some(uriPath)
        else None
      }
    }

    object TemplatePath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if(uriPath.isTemplatePath) Some(uriPath)
        else None
      }
    }

    object AliasesPath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if(uriPath.isAliasesPath) Some(uriPath)
        else None
      }
    }

    object CurrentUserMetadataPath {
      def unapply(uriPath: UriPath): Option[UriPath] = {
        if(uriPath.isCurrentUserMetadataPath) Some(uriPath)
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

  final case class DocumentWithIndex(index: IndexName, documentId: DocumentId)

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
          implicit class Ops (val specificField: SpecificField) extends AnyVal {
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

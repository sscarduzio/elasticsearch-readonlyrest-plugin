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
package tech.beshu.ror.utils

import better.files.File
import cats.data.{EitherT, NonEmptyList}
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Json, ParsingFailure, parser}
import io.jsonwebtoken.JwtBuilder
import io.lemonlabs.uri.Url
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers.*
import squants.information.Megabytes
import tech.beshu.ror.accesscontrol.audit.LoggingContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef.ImpersonatedUsers
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.GroupMappings
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.GroupMappings.Advanced.Mapping
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalAuthorizationService, ImpersonatorDef}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.ExternalAuthenticationServiceMock.ExternalAuthenticationUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.ExternalAuthorizationServiceMock.ExternalAuthorizationServiceUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.LdapServiceMock.LdapUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalAuthorizationServiceMock, LdapServiceMock}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, definitions}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain.KibanaApp.KibanaAppRegex
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.configuration.RawRorSettings
import tech.beshu.ror.es.EsVersion
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.js.{JsCompiler, MozillaJsCompiler}
import tech.beshu.ror.utils.json.JsonPath
import tech.beshu.ror.utils.misc.JwtUtils
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}
import tech.beshu.ror.utils.yaml.YamlParser

import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions
import scala.util.{Failure, Success}

object TestsUtils {

  implicit val loggingContext: LoggingContext = LoggingContext(Set.empty)
  val rorYamlParser = new YamlParser(Some(Megabytes(3)))

  val defaultEsVersionForTests: EsVersion = EsVersion(8, 17, 0)

  inline def nes(str : String): NonEmptyString = RefinedUtils.nes(str)

  def basicAuthHeader(value: String): Header =
    new Header(
      Header.Name.authorization,
      NonEmptyString.unsafeFrom(s"Basic ${Base64.getEncoder.encodeToString(value.getBytes)}")
    )

  def bearerHeader(jwt: JwtUtils.Jwt): Header =
    bearerHeader(Header.Name.authorization.value, jwt)

  def bearerHeader(headerName: NonEmptyString, jwt: JwtUtils.Jwt): Header =
    new Header(
      Header.Name(headerName),
      NonEmptyString.unsafeFrom(s"Bearer ${jwt.stringify()}")
    )

  def bearerHeader(jwt: JwtBuilder): Header =
    new Header(
      Header.Name.authorization,
      NonEmptyString.unsafeFrom(s"Bearer ${jwt.compact}")
    )

  def impersonationHeader(username: NonEmptyString): Header =
    new Header(Header.Name.impersonateAs, username)

  def header(name: String, value: String): Header =
    new Header(
      Name(NonEmptyString.unsafeFrom(name)),
      NonEmptyString.unsafeFrom(value)
    )

  def currentGroupHeader(value: String): Header =
    header("x-ror-current-group", value)

  def kibanaIndexName(str: NonEmptyString): KibanaIndexName = KibanaIndexName(localIndexName(str))

  def kibanaAppRegex(str: NonEmptyString): KibanaAppRegex = {
    implicit val compiler: JsCompiler = MozillaJsCompiler
    JsRegex.compile(str) match {
      case Right(jsRegex) => KibanaAppRegex(jsRegex)
      case Left(error) => throw new IllegalArgumentException(s"Cannot create KibanaAppRegex from '$str'; Cause: $error")
    }
  }

  def group(str: String): Group = Group.from(GroupId(NonEmptyString.unsafeFrom(str)))

  def group(id: String, name: String): Group = Group(GroupId(NonEmptyString.unsafeFrom(id)), GroupName(NonEmptyString.unsafeFrom(name)))

  def requestedIndex(str: NonEmptyString): RequestedIndex[ClusterIndexName] =
    RequestedIndex.fromString(str.value)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create RequestedIndex from '$str'"))

  def clusterIndexName(str: NonEmptyString): ClusterIndexName = ClusterIndexName.unsafeFromString(str.value)

  def localIndexName(str: NonEmptyString): ClusterIndexName.Local = ClusterIndexName.Local.fromString(str.value).get

  def fullLocalIndexWithAliases(fullIndexName: IndexName.Full): FullLocalIndexWithAliases =
    fullLocalIndexWithAliases(fullIndexName, Set.empty)

  def fullLocalIndexWithAliases(fullIndexName: IndexName.Full,
                                aliasesNames: Set[IndexName.Full]): FullLocalIndexWithAliases =
    FullLocalIndexWithAliases(fullIndexName, IndexAttribute.Opened, aliasesNames)

  def fullLocalDataStreamWithAliases(dataStreamName: DataStreamName.Full): FullLocalDataStreamWithAliases =
    fullLocalDataStreamWithAliases(
      dataStreamName = dataStreamName,
      aliasesNames = Set.empty,
    )

  def fullLocalDataStreamWithAliases(dataStreamName: DataStreamName.Full,
                                     aliasesNames: Set[DataStreamName.Full]): FullLocalDataStreamWithAliases =
    FullLocalDataStreamWithAliases(
      dataStreamName = dataStreamName,
      aliasesNames = aliasesNames,
      backingIndices = Set(IndexName.Full(NonEmptyString.unsafeFrom(".ds-" + dataStreamName.value.value)))
    )

  def fullRemoteDataStream(clusterName: ClusterName.Full, dataStreamName: DataStreamName.Full): FullRemoteDataStreamWithAliases =
    FullRemoteDataStreamWithAliases(
      clusterName = clusterName,
      dataStreamName = dataStreamName,
      aliasesNames = Set.empty,
      backingIndices = Set(IndexName.Full(NonEmptyString.unsafeFrom(".ds-" + dataStreamName.value.value)))
    )

  def remoteIndexName(str: NonEmptyString): ClusterIndexName.Remote = ClusterIndexName.Remote.fromString(str.value).get

  def indexName(str: NonEmptyString): IndexName = IndexName.fromString(str.value).get

  def fullIndexName(str: NonEmptyString): IndexName.Full = IndexName.Full.fromString(str.value).get

  def fullDataStreamName(str: NonEmptyString): DataStreamName.Full = DataStreamName.Full.fromNes(str.value)

  def indexPattern(str: NonEmptyString): IndexPattern = IndexPattern(clusterIndexName(str))

  def userId(str: NonEmptyString): User.Id = User.Id(str)

  implicit def scalaFiniteDuration2JavaDuration(duration: FiniteDuration): Duration = Duration.ofMillis(duration.toMillis)

  def impersonatorDefFrom(userIdPattern: NonEmptyString,
                          impersonatorCredentials: Credentials,
                          impersonatedUsersIdPatterns: NonEmptyList[NonEmptyString]): ImpersonatorDef = {
    ImpersonatorDef(
      userIdPatterns(userIdPattern.toString),
      new AuthKeyRule(
        BasicAuthenticationRule.Settings(impersonatorCredentials),
        CaseSensitivity.Enabled,
        Impersonation.Disabled
      ),
      ImpersonatedUsers(userIdPatterns(impersonatedUsersIdPatterns.head.toString, impersonatedUsersIdPatterns.map(_.toString).tail: _*))
    )
  }

  def mocksProviderForLdapFrom(map: Map[LdapService.Name, Map[User.Id, Set[Group]]]): MocksProvider = {
    new MocksProvider {
      override def ldapServiceWith(id: LdapService.Name)
                                  (implicit context: RequestId): Option[LdapServiceMock] = {
        map
          .get(id)
          .map(r => LdapServiceMock {
            r.map { case (userId, groups) => LdapUserMock(userId, groups) }.toCovariantSet
          })
      }

      override def externalAuthenticationServiceWith(id: ExternalAuthenticationService.Name)
                                                    (implicit context: RequestId): Option[ExternalAuthenticationServiceMock] = None

      override def externalAuthorizationServiceWith(id: ExternalAuthorizationService.Name)
                                                   (implicit context: RequestId): Option[ExternalAuthorizationServiceMock] = None
    }
  }

  def mocksProviderForExternalAuthnServiceFrom(map: Map[definitions.ExternalAuthenticationService.Name, Set[User.Id]]): MocksProvider = {
    new MocksProvider {
      override def ldapServiceWith(id: LdapService.Name)
                                  (implicit context: RequestId): Option[LdapServiceMock] = None

      override def externalAuthenticationServiceWith(id: ExternalAuthenticationService.Name)
                                                    (implicit context: RequestId): Option[ExternalAuthenticationServiceMock] = {
        map
          .get(id)
          .map(users => ExternalAuthenticationServiceMock(users.map(ExternalAuthenticationUserMock.apply)))
      }

      override def externalAuthorizationServiceWith(id: ExternalAuthorizationService.Name)
                                                   (implicit context: RequestId): Option[ExternalAuthorizationServiceMock] = None
    }
  }

  def mocksProviderForExternalAuthzServiceFrom(map: Map[definitions.ExternalAuthorizationService.Name, Map[User.Id, Set[Group]]]): MocksProvider = {
    new MocksProvider {
      override def ldapServiceWith(id: LdapService.Name)(implicit context: RequestId): Option[LdapServiceMock] = None

      override def externalAuthenticationServiceWith(id: ExternalAuthenticationService.Name)
                                                    (implicit context: RequestId): Option[ExternalAuthenticationServiceMock] = None

      override def externalAuthorizationServiceWith(id: ExternalAuthorizationService.Name)
                                                   (implicit context: RequestId): Option[ExternalAuthorizationServiceMock] = {
        map
          .get(id)
          .map(r => ExternalAuthorizationServiceMock {
            r.map { case (userId, groups) => ExternalAuthorizationServiceUserMock(userId, groups) }.toCovariantSet
          })
      }
    }
  }

  trait BlockContextAssertion {

    def assertBlockContext(expected: BlockContext,
                           current: BlockContext): Unit = {
      assertBlockContext(
        loggedUser = expected.userMetadata.loggedUser,
        currentGroup = expected.userMetadata.currentGroupId,
        availableGroups = expected.userMetadata.availableGroups,
        kibanaIndex = expected.userMetadata.kibanaIndex,
        kibanaTemplateIndex = expected.userMetadata.kibanaTemplateIndex,
        hiddenKibanaApps = expected.userMetadata.hiddenKibanaApps,
        kibanaAccess = expected.userMetadata.kibanaAccess,
        userOrigin = expected.userMetadata.userOrigin,
        jwt = expected.userMetadata.jwtToken,
        responseHeaders = expected.responseHeaders,
        indices = expected.indices,
        repositories = expected.repositories,
        snapshots = expected.snapshots) {
        current
      }
    }

    def assertBlockContext(loggedUser: Option[LoggedUser] = None,
                           currentGroup: Option[GroupId] = None,
                           availableGroups: UniqueList[Group] = UniqueList.empty,
                           kibanaIndex: Option[KibanaIndexName] = None,
                           kibanaTemplateIndex: Option[KibanaIndexName] = None,
                           hiddenKibanaApps: Set[KibanaApp] = Set.empty,
                           kibanaAccess: Option[KibanaAccess] = None,
                           userOrigin: Option[UserOrigin] = None,
                           jwt: Option[Jwt.Payload] = None,
                           responseHeaders: Set[Header] = Set.empty,
                           indices: Set[RequestedIndex[ClusterIndexName]] = Set.empty,
                           aliases: Set[ClusterIndexName] = Set.empty,
                           repositories: Set[RepositoryName] = Set.empty,
                           snapshots: Set[SnapshotName] = Set.empty,
                           dataStreams: Set[DataStreamName] = Set.empty,
                           templates: Set[TemplateOperation] = Set.empty)
                          (blockContext: BlockContext): Unit = {
      blockContext.userMetadata.loggedUser should be(loggedUser)
      blockContext.userMetadata.availableGroups should contain allElementsOf availableGroups
      blockContext.userMetadata.currentGroupId should be(currentGroup)
      blockContext.userMetadata.kibanaIndex should be(kibanaIndex)
      blockContext.userMetadata.kibanaTemplateIndex should be(kibanaTemplateIndex)
      blockContext.userMetadata.hiddenKibanaApps should be(hiddenKibanaApps)
      blockContext.userMetadata.kibanaAccess should be(kibanaAccess)
      blockContext.userMetadata.userOrigin should be(userOrigin)
      blockContext.userMetadata.jwtToken should be(jwt)
      blockContext.responseHeaders should be(responseHeaders)
      blockContext match {
        case _: CurrentUserMetadataRequestBlockContext =>
        case _: RorApiRequestBlockContext =>
        case _: GeneralNonIndexRequestBlockContext =>
        case bc: DataStreamRequestBlockContext =>
          bc.dataStreams should be(dataStreams)
        case bc: RepositoryRequestBlockContext =>
          bc.repositories should be(repositories)
        case bc: SnapshotRequestBlockContext =>
          bc.snapshots should be(snapshots)
          bc.repositories should be(repositories)
          bc.filteredIndices should be(indices)
        case bc: TemplateRequestBlockContext =>
          bc.templateOperation should be(templates)
        case bc: GeneralIndexRequestBlockContext =>
          bc.filteredIndices should be(indices)
        case bc: MultiIndexRequestBlockContext =>
          bc.indices should be(indices)
        case bc: FilterableRequestBlockContext =>
          bc.filteredIndices should be(indices)
        case bc: FilterableMultiRequestBlockContext =>
          bc.indices should be(indices)
        case bc: AliasRequestBlockContext =>
          bc.indices should be(indices)
          bc.aliases should be(aliases)
      }
    }
  }

  sealed trait AssertionType
  object AssertionType {
    final case class RuleFulfilled(blockContextAssertion: BlockContext => Unit) extends AssertionType
    final case class RuleRejected(cause: Option[Cause]) extends AssertionType
    final case class RuleThrownException(exception: Throwable) extends AssertionType
  }

  def headerFrom(nameAndValue: (String, String)): Header = {
    (NonEmptyString.unapply(nameAndValue._1), NonEmptyString.unapply(nameAndValue._2)) match {
      case (Some(nameNes), Some(valueNes)) => new Header(Name(nameNes), valueNes)
      case _ => throw new IllegalArgumentException(s"Cannot convert ${nameAndValue._1}:${nameAndValue._2} to Header")
    }
  }

  def requiredHeaderFrom(nameAndValue: (String, String)): AccessRequirement[Header] = {
    AccessRequirement.MustBePresent(headerFrom(nameAndValue))
  }

  def forbiddenHeaderFrom(nameAndValue: (String, String)): AccessRequirement[Header] = {
    AccessRequirement.MustBeAbsent(headerFrom(nameAndValue))
  }

  def headerNameFrom(name: String): Header.Name = {
    NonEmptyString.unapply(name) match {
      case Some(nameNes) => Header.Name(nameNes)
      case None => throw new IllegalArgumentException(s"Cannot convert $name to Header.Name")
    }
  }

  implicit class CurrentGroupToHeader(private val group: GroupId) extends AnyVal {
    def toCurrentGroupHeader: Header = currentGroupHeader(group.value.value)
  }

  def noGroupMappingFrom(value: String): GroupMappings =
    GroupMappings.Simple(UniqueNonEmptyList.of(group(value)))

  def groupMapping(mapping: Mapping, mappings: Mapping*): GroupMappings =
    GroupMappings.Advanced(UniqueNonEmptyList.of(mapping, mappings: _*))

  def apiKeyFrom(value: String): ApiKey = NonEmptyString.from(value) match {
    case Right(v) => ApiKey(v)
    case Left(_) => throw new IllegalArgumentException(s"Cannot convert $value to ApiKey")
  }

  def tokenFrom(value: String): Token = NonEmptyString.from(value) match {
    case Right(v) => Token(v)
    case Left(_) => throw new IllegalArgumentException(s"Cannot convert $value to Token")
  }

  def jsonPathFrom(value: String): JsonPath = JsonPath(value).get

  def urlFrom(value: String): Url = Url.parseTry(value) match {
    case Success(url) => url
    case Failure(ex) => throw new IllegalArgumentException(s"Cannot parse $value to Url: ${ex.getMessage}")
  }

  implicit class NonEmptyListOps[T](private val value: T) extends AnyVal {
    def nel: NonEmptyList[T] = NonEmptyList.one(value)
  }

  def rorSettingsFromUnsafe(yamlContent: String): RawRorSettings = {
    rorSettingFrom(yamlContent).toOption.get
  }

  def rorSettingFrom(yamlContent: String): Either[ParsingFailure, RawRorSettings] = {
    rorYamlParser
      .parse(yamlContent)
      .map(json => RawRorSettings(json, yamlContent))
  }

  def rorSettingsFromResource(resource: String): RawRorSettings = {
    rorSettingsFromUnsafe {
      getResourceContent(resource)
    }
  }

  def getResourcePath(resource: String): Path = {
    File(getClass.getResource(resource).getPath).path
  }

  def getResourceContent(resource: String): String = {
    File(getResourcePath(resource)).contentAsString
  }

  def circeJsonFrom(jsonString: String): Json = {
    parser.parse(jsonString).toTry.get
  }

  implicit class ValueOrIllegalState[ERROR, SUCCESS](private val eitherT: EitherT[Task, ERROR, SUCCESS]) extends AnyVal {

    def valueOrThrowIllegalState()(implicit scheduler: Scheduler): SUCCESS = {
      eitherT.value.runSyncUnsafe() match {
        case Right(value) => value
        case Left(error) => throw new IllegalStateException(s"$error")
      }
    }
  }
  
  implicit def unsafeNes(str: String): NonEmptyString = NonEmptyString.unsafeFrom(str)

  def userIdPatterns(id: String, ids: String*): UserIdPatterns = {
    UserIdPatterns(
      UniqueNonEmptyList.unsafeFrom(
        (id :: ids.toList).map(str => UserIdPattern(User.Id(NonEmptyString.unsafeFrom(str))))
      )
    )
  }
}

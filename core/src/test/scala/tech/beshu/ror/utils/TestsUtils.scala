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
import io.circe.{ACursor, Decoder, Json, parser}
import io.jsonwebtoken.JwtBuilder
import io.lemonlabs.uri.Url
import monix.eval.Task
import monix.execution.Scheduler
import org.scalamock.scalatest.MockFactory
import org.scalatest.TestSuite
import org.scalatest.matchers.should.Matchers.*
import squants.information.Megabytes
import tech.beshu.ror.accesscontrol.audit.LoggingContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef.ImpersonatedUsers
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.GroupMappings
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.GroupMappings.Advanced.Mapping
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalGroupsProviderService, ImpersonatorDef}
import tech.beshu.ror.accesscontrol.blocks.metadata.KibanaPolicy
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.ExternalAuthenticationServiceMock.ExternalAuthenticationUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.ExternalGroupsProviderServiceMock.ExternalGroupsProviderServiceUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.LdapServiceMock.LdapUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalGroupsProviderServiceMock, LdapServiceMock}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, ResponseTransformation, definitions}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix.StrictlyDefined
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenPrefix.{api, bearer}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain.KibanaApp.KibanaAppRegex
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.es.{EsEnv, EsNodeSettings, EsVersion}
import tech.beshu.ror.settings.ror.{RawRorSettings, RawRorSettingsYamlParser}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.js.{JsCompiler, MozillaJsCompiler}
import tech.beshu.ror.utils.json.JsonPath
import tech.beshu.ror.utils.misc.JwtUtils
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}
import tech.beshu.ror.utils.yaml.YamlParser

import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.{implicitConversions, postfixOps}
import scala.util.{Failure, Success, Try}

object TestsUtils {

  implicit val loggingContext: LoggingContext = LoggingContext(Set.empty)
  val rorYamlParser = new YamlParser(Some(Megabytes(3)))

  val defaultEsVersionForTests: EsVersion = EsVersion(8, 17, 0)

  inline def nes(str: String): NonEmptyString = RefinedUtils.nes(str)

  def basicAuthHeader(value: String): Header =
    new Header(
      Header.Name.authorization,
      NonEmptyString.unsafeFrom(s"Basic ${Base64.getEncoder.encodeToString(value.getBytes)}")
    )

  def bearerHeader(rawValue: String): Header =
    bearerHeader(Header.Name.authorization.value, rawValue)

  def bearerHeader(headerName: NonEmptyString, rawValue: String): Header = new Header(
    Header.Name(headerName),
    NonEmptyString.unsafeFrom(s"Bearer $rawValue")
  )

  def bearerHeader(jwt: JwtUtils.Jwt): Header =
    bearerHeader(Header.Name.authorization.value, jwt)

  def bearerHeader(headerName: NonEmptyString, jwt: JwtUtils.Jwt): Header = new Header(
    Header.Name(headerName),
    NonEmptyString.unsafeFrom(s"Bearer ${jwt.stringify()}")
  )

  def bearerHeader(jwt: JwtBuilder): Header = new Header(
    Header.Name.authorization,
    NonEmptyString.unsafeFrom(s"Bearer ${jwt.compact}")
  )

  def impersonationHeader(username: NonEmptyString): Header =
    new Header(Header.Name.impersonateAs, username)

  def header(name: String, value: String): Header = new Header(
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
    new FullLocalIndexWithAliases(fullIndexName, IndexAttribute.Opened, aliasesNames)

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

  def clusterName(str: NonEmptyString): ClusterName.Full = ClusterName.Full.fromString(str.value).get

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

      override def externalGroupsProviderServiceWith(id: ExternalGroupsProviderService.Name)
                                                    (implicit context: RequestId): Option[ExternalGroupsProviderServiceMock] = None
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

      override def externalGroupsProviderServiceWith(id: ExternalGroupsProviderService.Name)
                                                    (implicit context: RequestId): Option[ExternalGroupsProviderServiceMock] = None
    }
  }

  def mocksProviderForExternalAuthzServiceFrom(map: Map[ExternalGroupsProviderService.Name, Map[User.Id, Set[Group]]]): MocksProvider = {
    new MocksProvider {
      override def ldapServiceWith(id: LdapService.Name)(implicit context: RequestId): Option[LdapServiceMock] = None

      override def externalAuthenticationServiceWith(id: ExternalAuthenticationService.Name)
                                                    (implicit context: RequestId): Option[ExternalAuthenticationServiceMock] = None

      override def externalGroupsProviderServiceWith(id: ExternalGroupsProviderService.Name)
                                                    (implicit context: RequestId): Option[ExternalGroupsProviderServiceMock] = {
        map
          .get(id)
          .map(r => ExternalGroupsProviderServiceMock {
            r.map { case (userId, groups) => ExternalGroupsProviderServiceUserMock(userId, groups) }.toCovariantSet
          })
      }
    }
  }

  trait BlockContextAssertion extends MockFactory {
    this: TestSuite =>

    def assertBlockContext(blockContext: BlockContext)
                          (loggedUser: Option[LoggedUser] = None,
                           currentGroup: Option[GroupId] = None,
                           availableGroups: UniqueList[Group] = UniqueList.empty,
                           kibanaPolicy: Option[KibanaPolicy] = None,
                           userOrigin: Option[UserOrigin] = None,
                           jwt: Option[Jwt.Payload] = None,
                           responseHeaders: Set[Header] = Set.empty,
                           responseTransformations: List[ResponseTransformation] = List.empty,
                           indices: Set[RequestedIndex[ClusterIndexName]] = Set.empty,
                           indexPacks: List[Indices] = List.empty,
                           aliases: Set[ClusterIndexName] = Set.empty,
                           repositories: Set[RepositoryName] = Set.empty,
                           snapshots: Set[SnapshotName] = Set.empty,
                           dataStreams: Set[DataStreamName] = Set.empty,
                           templates: Set[TemplateOperation] = Set.empty,
                           filter: Option[Filter] = None): Unit = {
      blockContext.blockMetadata.loggedUser should be(loggedUser)
      blockContext.blockMetadata.availableGroups should contain allElementsOf availableGroups
      blockContext.blockMetadata.currentGroupId should be(currentGroup)
      blockContext.blockMetadata.kibanaPolicy should be(kibanaPolicy)
      blockContext.blockMetadata.userOrigin should be(userOrigin)
      blockContext.blockMetadata.jwtToken should be(jwt)
      blockContext.responseHeaders should be(responseHeaders)
      blockContext.responseTransformations should be(responseTransformations)
      blockContext match {
        case _: UserMetadataRequestBlockContext =>
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
          bc.filter should be(filter)
        case bc: FilterableMultiRequestBlockContext =>
          bc.indexPacks should be(indexPacks)
          bc.filter should be(filter)
        case bc: AliasRequestBlockContext =>
          bc.indices should be(indices)
          bc.aliases should be(aliases)
      }
    }
  }

  sealed trait RuleCheckAssertion
  object RuleCheckAssertion {
    final case class RulePermitted(blockContextAssertion: BlockContext => Unit) extends RuleCheckAssertion
    final case class RuleDenied(cause: Cause) extends RuleCheckAssertion
    final case class RuleThrownException(exception: Throwable) extends RuleCheckAssertion
  }

  extension (rule: Rule) {
    def checkAndAssert[B <: BlockContext : BlockContextUpdater](blockContext: B, assertion: RuleCheckAssertion): Unit = {
      import monix.execution.Scheduler.Implicits.global
      val result = Try(rule.check(blockContext).runSyncUnsafe(1 second))
      assertion match {
        case RuleCheckAssertion.RulePermitted(blockContextAssertion) =>
          result.get shouldBe a[Permitted[B]]
          blockContextAssertion(result.get.asInstanceOf[Permitted[B]].context)
        case RuleCheckAssertion.RuleDenied(cause) =>
          result.get should be(Denied(cause))
        case RuleCheckAssertion.RuleThrownException(ex) =>
          result should be(Failure(ex))
      }
    }
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

  def authorizationTokenFrom(value: String): AuthorizationToken = (for {
    nes <- NonEmptyString.from(value)
    token <- AuthorizationToken.from(nes).toRight("Cannot create authorization token")
  } yield token) match {
    case Right(v) => v
    case Left(msg) => throw new IllegalArgumentException(s"Cannot convert $value to AuthorizationToken; $msg")
  }

  def strictlyDefinedBearerTokenDef = AuthorizationTokenDef(Header.Name.authorization, StrictlyDefined(bearer))

  def anyTokenDef = AuthorizationTokenDef(Header.Name.authorization, AllowedPrefix.Any)

  def apiKeyDef = AuthorizationTokenDef(Header.Name.authorization, StrictlyDefined(api))

  def jsonPathFrom(value: String): JsonPath = JsonPath(value).get

  def urlFrom(value: String): Url = Url.parseTry(value) match {
    case Success(url) => url
    case Failure(ex) => throw new IllegalArgumentException(s"Cannot parse $value to Url: ${ex.getMessage}")
  }

  implicit class NonEmptyListOps[T](private val value: T) extends AnyVal {
    def nel: NonEmptyList[T] = NonEmptyList.one(value)
  }

  def rorSettingsFromUnsafe(yamlContent: String): RawRorSettings = {
    new RawRorSettingsYamlParser(Megabytes(1))
      .fromString(yamlContent)
      .left.map { e => new IllegalStateException(s"Error: $e") }
      .toTry.get
  }

  def rorSettingsFromResource(resource: String): RawRorSettings = {
    rorSettingsFromUnsafe {
      getResourceContent(resource)
    }
  }

  def getResourcePath(resource: String): Path = {
    File(getClass.getResource(resource).toURI).path
  }

  def getResourceContent(resource: String): String = {
    File(getResourcePath(resource)).contentAsString
  }

  def circeJsonFrom(jsonString: String): Json = {
    parser.parse(jsonString).toTry.get
  }

  def createEsEnv(configDir: File): EsEnv = {
    val xpackSecurityEnabled = loadPathFrom(configDir, NonEmptyList.of("xpack", "security", "enabled"), true)
    EsEnv(
      configDir = configDir,
      modulesDir = configDir,
      esVersion = defaultEsVersionForTests,
      esNodeSettings = EsNodeSettings(
        clusterName = "testEsCluster",
        nodeName = "testEsNode",
        xpackSecurityEnabled = xpackSecurityEnabled
      ))
  }

  def defaultTestEsNodeSettings: EsNodeSettings = EsNodeSettings(
    clusterName = "testEsCluster",
    nodeName = "testEsNode",
    xpackSecurityEnabled = true
  )

  def defaultEsEnv(esConfig: Option[File] = None): EsEnv = {
    EsEnv(esConfig.getOrElse(File("/config")), File("/modules"), defaultEsVersionForTests, defaultTestEsNodeSettings)
  }

  private def loadPathFrom[T: Decoder](configDir: File, path: NonEmptyList[String], default: T) = {
    rorYamlParser
      .parse((configDir / "elasticsearch.yml").contentAsString)
      .map { json =>
        val oneLineCursor = json.hcursor.downField(path.toList.mkString("."))
        val multiLineCursor = path.foldLeft[ACursor](json.hcursor)((c, segment) => c.downField(segment))
        oneLineCursor.as[Option[T]] match {
          case Right(Some(value)) => value
          case Right(None) =>
            multiLineCursor.as[Option[T]] match {
              case Right(Some(value)) => value
              case Right(None) => default
              case Left(error) => throw error
            }
          case Left(error) => throw error
        }
      } match {
      case Right(value) => value
      case Left(error) => throw error
    }
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

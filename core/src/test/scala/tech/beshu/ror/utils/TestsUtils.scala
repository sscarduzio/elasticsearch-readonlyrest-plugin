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

import java.nio.file.Path
import java.time.Duration
import java.util.Base64

import better.files.File
import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.ParsingFailure
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{AliasRequestBlockContext, CurrentUserMetadataRequestBlockContext, FilterableMultiRequestBlockContext, FilterableRequestBlockContext, GeneralIndexRequestBlockContext, GeneralNonIndexRequestBlockContext, MultiIndexRequestBlockContext, RepositoryRequestBlockContext, SnapshotRequestBlockContext, TemplateRequestBlockContext}
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.logging.LoggingContext
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

object TestsUtils {

  implicit val loggingContext: LoggingContext = LoggingContext(Set.empty)

  def basicAuthHeader(value: String): Header =
    new Header(
      Name(NonEmptyString.unsafeFrom("Authorization")),
      NonEmptyString.unsafeFrom("Basic " + Base64.getEncoder.encodeToString(value.getBytes))
    )

  def header(name: String, value: String): Header =
    new Header(
      Name(NonEmptyString.unsafeFrom(name)),
      NonEmptyString.unsafeFrom(value)
    )

  def clusterIndexName(str: NonEmptyString): ClusterIndexName = ClusterIndexName.unsafeFromString(str.value)

  def localIndexName(str: NonEmptyString): ClusterIndexName.Local = ClusterIndexName.Local.fromString(str.value.value).get

  def remoteIndexName(str: NonEmptyString): ClusterIndexName.Remote = ClusterIndexName.Remote.fromString(str.value.value).get

  def indexName(str: NonEmptyString): IndexName = IndexName.fromString(str.value).get

  def fullIndexName(str: NonEmptyString): IndexName.Full = IndexName.Full.fromString(str.value).get

  def indexPattern(str: NonEmptyString): IndexPattern = IndexPattern(clusterIndexName(str))

  implicit def scalaFiniteDuration2JavaDuration(duration: FiniteDuration): Duration = Duration.ofMillis(duration.toMillis)

  trait BlockContextAssertion {

    def assertBlockContext(expected: BlockContext,
                           current: BlockContext): Unit = {
      assertBlockContext(
        loggedUser = expected.userMetadata.loggedUser,
        currentGroup = expected.userMetadata.currentGroup,
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
                           currentGroup: Option[Group] = None,
                           availableGroups: UniqueList[Group] = UniqueList.empty,
                           kibanaIndex: Option[ClusterIndexName] = None,
                           kibanaTemplateIndex: Option[ClusterIndexName] = None,
                           hiddenKibanaApps: Set[KibanaApp] = Set.empty,
                           kibanaAccess: Option[KibanaAccess] = None,
                           userOrigin: Option[UserOrigin] = None,
                           jwt: Option[JwtTokenPayload] = None,
                           responseHeaders: Set[Header] = Set.empty,
                           indices: Set[ClusterIndexName] = Set.empty,
                           aliases: Set[ClusterIndexName] = Set.empty,
                           repositories: Set[RepositoryName] = Set.empty,
                           snapshots: Set[SnapshotName] = Set.empty,
                           templates: Set[TemplateOperation] = Set.empty)
                          (blockContext: BlockContext): Unit = {
      blockContext.userMetadata.loggedUser should be(loggedUser)
      blockContext.userMetadata.currentGroup should be(currentGroup)
      blockContext.userMetadata.availableGroups should be(availableGroups)
      blockContext.userMetadata.kibanaIndex should be(kibanaIndex)
      blockContext.userMetadata.kibanaTemplateIndex should be(kibanaTemplateIndex)
      blockContext.userMetadata.hiddenKibanaApps should be(hiddenKibanaApps)
      blockContext.userMetadata.kibanaAccess should be(kibanaAccess)
      blockContext.userMetadata.userOrigin should be(userOrigin)
      blockContext.userMetadata.jwtToken should be(jwt)
      blockContext.responseHeaders should be(responseHeaders)
      blockContext match {
        case _: CurrentUserMetadataRequestBlockContext =>
        case _: GeneralNonIndexRequestBlockContext =>
        case bc: RepositoryRequestBlockContext =>
          bc.repositories should be (repositories)
        case bc: SnapshotRequestBlockContext =>
          bc.snapshots should be (snapshots)
          bc.repositories should be (repositories)
          bc.filteredIndices should be (indices)
        case bc: TemplateRequestBlockContext =>
          bc.templateOperation  should be (templates)
        case bc: GeneralIndexRequestBlockContext =>
          bc.filteredIndices should be (indices)
        case bc: MultiIndexRequestBlockContext =>
          bc.indices should be (indices)
        case bc: FilterableRequestBlockContext =>
          bc.filteredIndices should be (indices)
        case bc: FilterableMultiRequestBlockContext =>
          bc.indices should be (indices)
        case bc: AliasRequestBlockContext =>
          bc.indices should be (indices)
          bc.aliases should be (aliases)
      }
    }
  }

  sealed trait AssertionType
  object AssertionType {
    final case class RuleFulfilled(blockContextAssertion: BlockContext => Unit) extends AssertionType
    object RuleRejected extends AssertionType
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

  def groupFrom(value: String): Group = NonEmptyString.from(value) match {
    case Right(v) => Group(v)
    case Left(_) => throw new IllegalArgumentException(s"Cannot convert $value to Group")
  }

  def apiKeyFrom(value: String): ApiKey = NonEmptyString.from(value) match {
    case Right(v) => ApiKey(v)
    case Left(_) => throw new IllegalArgumentException(s"Cannot convert $value to ApiKey")
  }

  implicit class NonEmptyListOps[T](val value: T) extends AnyVal {
    def nel: NonEmptyList[T] = NonEmptyList.one(value)
  }

  def rorConfigFromUnsafe(yamlContent: String): RawRorConfig = {
    RawRorConfig(yaml.parser.parse(yamlContent).right.get, yamlContent)
  }

  def rorConfigFrom(yamlContent: String): Either[ParsingFailure, RawRorConfig] = {
    yaml.parser.parse(yamlContent).map(json => RawRorConfig(json, yamlContent))
  }

  def rorConfigFromResource(resource: String): RawRorConfig = {
    rorConfigFromUnsafe {
      getResourceContent(resource)
    }
  }

  def getResourcePath(resource: String): Path = {
    File(getClass.getResource(resource).getPath).path
  }

  def getResourceContent(resource: String): String = {
    File(getResourcePath(resource)).contentAsString
  }

}

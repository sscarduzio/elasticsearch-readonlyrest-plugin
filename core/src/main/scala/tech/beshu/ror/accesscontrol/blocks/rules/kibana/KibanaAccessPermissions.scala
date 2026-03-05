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
package tech.beshu.ror.accesscontrol.blocks.rules.kibana

import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaActionMatchers.*
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.KibanaAccess.*
import tech.beshu.ror.accesscontrol.domain.KibanaIndexName.*
import tech.beshu.ror.implicits.*

import java.util.regex.Pattern
import scala.util.Try

object KibanaAccessPermissions {

  sealed trait ResourceCategory
  object ResourceCategory {
    case object KibanaIndex extends ResourceCategory
    case object ReportingIndex extends ResourceCategory
    case object DataIndex extends ResourceCategory
    case object SampleData extends ResourceCategory
    case object RorSettingsIndex extends ResourceCategory
    case object NoIndices extends ResourceCategory
    case object UserMetadataRequest extends ResourceCategory
    case object DevNullKibana extends ResourceCategory
  }

  sealed trait ActionCategory {
    def label: String
  }
  object ActionCategory {
    case object ReadOnly extends ActionCategory { val label = "read-only" }
    case object Cluster extends ActionCategory { val label = "cluster" }
    case object ReadWrite extends ActionCategory { val label = "read-write" }
    case object Admin extends ActionCategory { val label = "admin" }
    case object IndicesWrite extends ActionCategory { val label = "indices-write" }
    case object Other extends ActionCategory { val label = "other" }
  }

  sealed trait Permission {
    def label: String
  }
  object Permission {
    case object Full extends Permission { val label = "full" }
    case object ReadOnly extends Permission { val label = "read-only" }
    case object None extends Permission { val label = "none" }
  }

  sealed trait MatchVerdict {
    def reason: String
    def isAllowed: Boolean
  }
  object MatchVerdict {
    final case class Allowed(reason: String) extends MatchVerdict {
      val isAllowed = true
    }
    final case class Denied(reason: String) extends MatchVerdict {
      val isAllowed = false
    }
  }

  object RequestClassifier {

    def classifyResource(bc: BlockContext,
                         kibanaIndex: KibanaIndexName,
                         rorIndex: RorSettingsIndex): ResourceCategory = {
      val path = bc.requestContext.restRequest.path
      if (path.isCurrentUserMetadataPath)
        return ResourceCategory.UserMetadataRequest

      val indices = bc.indices
      val dataStreams = bc.dataStreams

      if (indices.size == 1 && indices.head.name == devNullKibana.underlying)
        return ResourceCategory.DevNullKibana

      if (isSampleDataIndex(indices) || isSampleDataStream(dataStreams))
        return ResourceCategory.SampleData

      if (indices.size == 1 && indices.head.name == rorIndex.toLocal)
        return ResourceCategory.RorSettingsIndex

      if (indices.isEmpty && dataStreams.isEmpty)
        return ResourceCategory.NoIndices

      if (indices.nonEmpty && indices.forall(i => isReportingIndex(i.name, kibanaIndex)))
        return ResourceCategory.ReportingIndex

      if (indices.nonEmpty && indices.forall(_.name.isRelatedToKibanaIndex(kibanaIndex)))
        return ResourceCategory.KibanaIndex

      ResourceCategory.DataIndex
    }

    def classifyAction(bc: BlockContext): ActionCategory = {
      val action = bc.requestContext.action
      if (roActionPatternsMatcher.`match`(action)) ActionCategory.ReadOnly
      else if (clusterActionPatternsMatcher.`match`(action)) ActionCategory.Cluster
      else if (rwActionPatternsMatcher.`match`(action)) ActionCategory.ReadWrite
      else if (adminActionPatternsMatcher.`match`(action)) ActionCategory.Admin
      else if (indicesWriteAction.`match`(action)) ActionCategory.IndicesWrite
      else ActionCategory.Other
    }

    def isNonStrictEligible(bc: BlockContext, kibanaIndex: KibanaIndexName): Boolean = {
      val path = bc.requestContext.restRequest.path
      val action = bc.requestContext.action
      val nonStrictAllowedPaths = Try(Pattern.compile(
        "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*|doc/url.*)/.*|^/_template/.*|^/@kibana_index/doc/telemetry.*|^/@kibana_index/(_update/index-pattern.*|_update/url.*)|^/@kibana_index/_create/(url:.*)"
          .replace("@kibana_index", kibanaIndex.stringify)
      )).toOption
      val pathMatch = nonStrictAllowedPaths.exists(_.matcher(path.value.value).find())
      pathMatch && nonStrictActions.`match`(action)
    }

    def hasAdminHeaderPath(bc: BlockContext, pathPart: String): Boolean = {
      bc.requestContext
        .restRequest
        .allHeaders
        .find(_.name === Header.Name.kibanaRequestPath)
        .exists(_.value.value.contains(s"/$pathPart/"))
    }

    private def isReportingIndex(indexName: ClusterIndexName, kibanaIndex: KibanaIndexName): Boolean = {
      val kibanaStr = kibanaIndex.stringify
      val indexStr = indexName.stringify
      indexStr.startsWith(s"$kibanaStr-reporting-") ||
        indexStr.startsWith(s".ds-$kibanaStr-reporting-")
    }

    private def isSampleDataIndex(indices: Iterable[RequestedIndex[ClusterIndexName]]): Boolean = {
      indices.toList match {
        case head :: Nil => kibanaSampleDataIndexMatcher.`match`(head.name)
        case _ => false
      }
    }

    private def isSampleDataStream(dataStreams: Iterable[DataStreamName]): Boolean = {
      dataStreams.toList match {
        case head :: Nil => kibanaSampleDataStreamMatcher.`match`(head)
        case _ => false
      }
    }
  }

  object PermissionTable {
    import ResourceCategory.*

    def lookup(access: KibanaAccess, resource: ResourceCategory, bc: BlockContext): Permission = {
      access match {
        case Unrestricted => forUnrestricted(resource)
        case Admin        => forAdmin(resource, bc)
        case RW           => forRW(resource)
        case RO           => forRO(resource)
        case ROStrict     => forROStrict(resource)
        case ApiOnly      => forApiOnly(resource)
      }
    }

    //                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
    // unrestricted       | full         | full         | full      | full         | full         |
    private def forUnrestricted(resource: ResourceCategory): Permission = Permission.Full

    //                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
    // admin              | full         | read-only    | full      | full         | full         |
    private def forAdmin(resource: ResourceCategory, bc: BlockContext): Permission = resource match {
      case UserMetadataRequest | DevNullKibana       => Permission.Full
      case KibanaIndex | SampleData                  => Permission.Full
      case ReportingIndex                            => Permission.Full
      case RorSettingsIndex                          => Permission.Full
      case NoIndices                                 => Permission.Full
      case DataIndex if hasAdminHeaderPath(bc)       => Permission.Full
      case DataIndex                                 => Permission.ReadOnly
    }

    //                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
    // rw                 | full         | read-only    | full      | full         | none         |
    private def forRW(resource: ResourceCategory): Permission = resource match {
      case UserMetadataRequest | DevNullKibana       => Permission.Full
      case KibanaIndex | SampleData                  => Permission.Full
      case ReportingIndex                            => Permission.Full
      case NoIndices                                 => Permission.Full
      case RorSettingsIndex                          => Permission.None
      case DataIndex                                 => Permission.ReadOnly
    }

    //                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
    // ro                 | read-only*   | read-only    | full      | read-only    | none         |
    private def forRO(resource: ResourceCategory): Permission = resource match {
      case UserMetadataRequest | DevNullKibana       => Permission.Full
      case KibanaIndex | SampleData                  => Permission.ReadOnly
      case ReportingIndex                            => Permission.Full
      case NoIndices                                 => Permission.ReadOnly
      case RorSettingsIndex                          => Permission.None
      case DataIndex                                 => Permission.ReadOnly
    }

    //                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
    // ro_strict          | read-only    | read-only    | none      | read-only    | none         |
    private def forROStrict(resource: ResourceCategory): Permission = resource match {
      case UserMetadataRequest | DevNullKibana       => Permission.Full
      case KibanaIndex | SampleData                  => Permission.ReadOnly
      case ReportingIndex                            => Permission.None
      case NoIndices                                 => Permission.ReadOnly
      case RorSettingsIndex                          => Permission.None
      case DataIndex                                 => Permission.ReadOnly
    }

    //                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
    // api_only           | read-only    | read-only    | none      | read-only    | none         |
    private def forApiOnly(resource: ResourceCategory): Permission = resource match {
      case UserMetadataRequest | DevNullKibana       => Permission.Full
      case KibanaIndex | SampleData                  => Permission.ReadOnly
      case ReportingIndex                            => Permission.None
      case NoIndices                                 => Permission.ReadOnly
      case RorSettingsIndex                          => Permission.None
      case DataIndex                                 => Permission.ReadOnly
    }

    private def hasAdminHeaderPath(bc: BlockContext): Boolean = {
      RequestClassifier.hasAdminHeaderPath(bc, "index_management") ||
        RequestClassifier.hasAdminHeaderPath(bc, "tags")
    }
  }

  object PermissionEvaluator {

    def evaluate(permission: Permission,
                 actionCategory: ActionCategory,
                 access: KibanaAccess,
                 resource: ResourceCategory,
                 nonStrictEligible: Boolean): MatchVerdict = {
      val accessLabel = accessToLabel(access)
      val resourceLabel = resourceToLabel(resource)

      permission match {
        case Permission.Full =>
          actionCategory match {
            case ActionCategory.Other =>
              MatchVerdict.Denied(
                s"Access level '$accessLabel' has full permission on '$resourceLabel', but action type '${actionCategory.label}' is not recognized"
              )
            case _ =>
              MatchVerdict.Allowed(
                s"Access level '$accessLabel' has full permission on '$resourceLabel'"
              )
          }

        case Permission.ReadOnly =>
          actionCategory match {
            case ActionCategory.ReadOnly | ActionCategory.Cluster =>
              MatchVerdict.Allowed(
                s"Access level '$accessLabel' allows ${actionCategory.label} actions on '$resourceLabel'"
              )
            case ActionCategory.ReadWrite | ActionCategory.IndicesWrite if nonStrictEligible && resource == ResourceCategory.KibanaIndex =>
              MatchVerdict.Allowed(
                s"Access level '$accessLabel' allows non-strict write on '$resourceLabel' (UI state save)"
              )
            case _ =>
              MatchVerdict.Denied(
                s"Access level '$accessLabel' only allows read-only access to '$resourceLabel', but action is '${actionCategory.label}'"
              )
          }

        case Permission.None =>
          MatchVerdict.Denied(
            s"Access level '$accessLabel' has no permission on '$resourceLabel'"
          )
      }
    }

    private def accessToLabel(access: KibanaAccess): String = access match {
      case RO => "ro"
      case RW => "rw"
      case ROStrict => "ro_strict"
      case Admin => "admin"
      case Unrestricted => "unrestricted"
      case ApiOnly => "api_only"
    }

    private def resourceToLabel(resource: ResourceCategory): String = resource match {
      case ResourceCategory.KibanaIndex => "kibana index"
      case ResourceCategory.ReportingIndex => "reporting index"
      case ResourceCategory.DataIndex => "data index"
      case ResourceCategory.SampleData => "sample data"
      case ResourceCategory.RorSettingsIndex => "ror settings index"
      case ResourceCategory.NoIndices => "no-index request"
      case ResourceCategory.UserMetadataRequest => "user metadata request"
      case ResourceCategory.DevNullKibana => "devnull kibana"
    }
  }
}

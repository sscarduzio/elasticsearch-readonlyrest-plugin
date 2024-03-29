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

import cats.implicits.toShow
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaUserDataRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.ResolvableJsonRepresentationOps._
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Json.ResolvableJsonRepresentation
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class KibanaUserDataRule(override val settings: Settings)
  extends BaseKibanaRule(settings)  {

  override val name: Rule.Name = KibanaUserDataRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    if (shouldMatch(blockContext.requestContext, resolveKibanaIndex(blockContext)))
      matched(blockContext)
    else
      Rejected[B]()
  }

  private def matched[B <: BlockContext : BlockContextUpdater](blockContext: B): Fulfilled[B] = {
    RuleResult.Fulfilled[B] {
      blockContext.withUserMetadata {
        updateUserMetadata(blockContext)
      }
    }
  }

  private def updateUserMetadata(using: BlockContext) = {
    applyToUserMetadata(Some(settings.access))(
      _.withKibanaAccess(_)
    ) andThen {
      applyToUserMetadata(Some(resolveKibanaIndex(using)))(
        _.withKibanaIndex(_)
      )
    } andThen {
      applyToUserMetadata(resolveKibanaIndexTemplate(using))(
        _.withKibanaTemplateIndex(_)
      )
    } andThen {
      applyToUserMetadata(resolveAppsToHide)(
        _.withHiddenKibanaApps(_)
      )
    } andThen {
      applyToUserMetadata(resolveAllowedApiPaths)(
        _.withAllowedKibanaApiPaths(_)
      )
    } andThen {
      applyToUserMetadata(resolvedKibanaMetadata(using))(
        _.withKibanaMetadata(_)
      )
    }
  }

  private def resolveKibanaIndex(using: BlockContext) =
    settings.kibanaIndex.resolve(using).toTry.get

  private def resolveKibanaIndexTemplate(using: BlockContext) =
    settings
      .kibanaTemplateIndex
      .flatMap {
        _.resolve(using) match {
          case Right(resolvedKibanaIndexTemplate) =>
            Some(resolvedKibanaIndexTemplate)
          case Left(error) =>
            logger.warn(s"[${using.requestContext.id.show}] Cannot resolve variable(s) used in Kibana template index name; error: ${error.show}")
            None
        }
      }

  private lazy val resolveAppsToHide =
    UniqueNonEmptyList.fromIterable(settings.appsToHide)

  private lazy val resolveAllowedApiPaths =
    UniqueNonEmptyList.fromIterable(settings.allowedApiPaths)

  private def resolvedKibanaMetadata(using: BlockContext) =
    settings
      .metadata
      .flatMap {
        _.resolve(using) match {
          case Right(resolvedKibanaMetadata) =>
            Some(resolvedKibanaMetadata)
          case Left(error) =>
            logger.warn(s"[${using.requestContext.id.show}] Cannot resolve variable(s) used in Kibana metadata; error: ${error.show}")
            None
        }
      }

  private def applyToUserMetadata[T](opt: Option[T])
                                    (userMetadataUpdateFunction: (UserMetadata, T) => UserMetadata): UserMetadata => UserMetadata = {
    opt match {
      case Some(value) => userMetadataUpdateFunction(_, value)
      case None => identity[UserMetadata]
    }
  }
}

object KibanaUserDataRule {

  implicit case object Name extends RuleName[KibanaUserDataRule] {
    override val name = Rule.Name("kibana")
  }

  final case class Settings(override val access: KibanaAccess,
                            kibanaIndex: RuntimeSingleResolvableVariable[KibanaIndexName],
                            kibanaTemplateIndex: Option[RuntimeSingleResolvableVariable[KibanaIndexName]],
                            appsToHide: Set[KibanaApp],
                            allowedApiPaths: Set[KibanaAllowedApiPath],
                            metadata: Option[ResolvableJsonRepresentation],
                            override val rorIndex: RorConfigurationIndex)
    extends BaseKibanaRule.Settings(access, rorIndex)
}
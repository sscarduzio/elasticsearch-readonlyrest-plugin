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
package tech.beshu.ror.es.request.context.types

import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect._
import tech.beshu.ror.accesscontrol.domain.TemplateLike.IndexTemplate
import tech.beshu.ror.accesscontrol.domain.{IndexName, TemplateName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._

class CreateTemplateEsRequestContext(actionRequest: PutIndexTemplateRequest,
                                     esContext: EsContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseSingleTemplateEsRequestContext[PutIndexTemplateRequest, IndexTemplate](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateFrom(request: PutIndexTemplateRequest): IndexTemplate = {
    val templateName = NonEmptyString
      .from(request.name())
      .map(TemplateName.apply)
      .getOrElse(throw RequestSeemsToBeInvalid[PutIndexTemplateRequest]("PutIndexTemplateRequest template name cannot be empty"))

    val indexPatterns = UniqueNonEmptyList
      .fromList(request.patterns().asScala.flatMap(IndexName.fromString).toList)
      .getOrElse(throw RequestSeemsToBeInvalid[PutIndexTemplateRequest]("PutIndexTemplateRequest is required to have at least one index pattern"))

    val aliases = request.aliases().asScala.flatMap(a => IndexName.fromString(a.name())).toSet

    IndexTemplate(templateName, indexPatterns, aliases)
  }

  override protected def update(request: PutIndexTemplateRequest, template: IndexTemplate): ModificationResult = {
    request.patterns(template.patterns.map(_.value.value).toList.asJava)
    on(request).call("aliases", filterRequestAliasesWith(template).asJava)
    ModificationResult.Modified
  }

  private def filterRequestAliasesWith(template: IndexTemplate) = {
    val allowedAliasesNames = template.aliases.map(_.value.value)
    actionRequest
      .aliases().asScala
      .filter(a => allowedAliasesNames.contains(a.name()))
      .toSet
  }
}

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
package tech.beshu.ror.es.providers

import org.elasticsearch.cluster.metadata.MetaDataIndexTemplateService
import org.elasticsearch.cluster.service.ClusterService
import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances._
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._

import scala.collection.JavaConverters._

class EsServerBasedRorClusterService(clusterService: ClusterService) extends RorClusterService {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = {
    val lookup = clusterService.state.metaData.getAliasAndIndexLookup
    lookup.get(indexOrAlias.value.value).getIndices.asScala.map(_.getIndexUUID).toSet
  }

  override def allIndices: Set[IndexName] =
    clusterService
      .state
      .getMetaData
      .getIndices.keysIt.asScala.toSet
      .flatMap(IndexName.fromString)

  override def findTemplatesOfIndices(indices: Set[IndexName]): Set[IndexName] = {
    val metaData = clusterService.state.getMetaData
    indices
      .flatMap(index => MetaDataIndexTemplateService.findTemplates(metaData, index.value.value).asScala)
      .map(_.getName)
      .flatMap(IndexName.fromString)
  }

  override def getTemplatesWithPatterns: Map[TemplateName, Set[IndexPatten]] =
    Option(clusterService.state.getMetaData.templates)
      .toList
      .flatMap(t => t.iterator().asScala.map(t => (t.key, t.value.patterns().asScala.toSet)))
      .toMap

  override def allIndicesAndAliases: Map[IndexName, Set[AliasName]] = {
    val indices = clusterService.state.metaData.getIndices
    indices
      .keysIt().asScala
      .flatMap { index =>
        val indexMetaData = indices.get(index)
        IndexName
          .fromString(indexMetaData.getIndex.getName)
          .map { indexName =>
            val aliases = indexMetaData.getAliases.keysIt.asScala.toSet.flatMap(IndexName.fromString)
            (indexName, aliases)
          }
      }
      .toMap
  }

  override def expandIndices(indices: Set[domain.IndexName]): Set[domain.IndexName] = {
    val all = allIndicesAndAliases
      .flatMap { case (indexName, aliases) => aliases + indexName}
      .toSet
    MatcherWithWildcardsScalaAdapter.create(indices).filter(all)
  }
}
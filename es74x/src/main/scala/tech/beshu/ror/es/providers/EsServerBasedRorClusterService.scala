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
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._

import scala.collection.JavaConverters._

class EsServerBasedRorClusterService(clusterService: ClusterService) extends RorClusterService {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = {
    val lookup = clusterService.state.metaData.getAliasAndIndexLookup
    lookup.get(indexOrAlias).getIndices.asScala.map(_.getIndexUUID).toSet
  }

  override def allIndices: Set[IndexName] = clusterService.state.getMetaData.getIndices.keysIt.asScala.toSet

  override def findTemplatesOfIndices(indices: Set[IndexName]): Set[IndexName] = {
    val metaData = clusterService.state.getMetaData
    indices
      .flatMap(index => MetaDataIndexTemplateService.findTemplates(metaData, index).asScala)
      .map(_.getName)
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
      .map { index =>
        val indexMetaData = indices.get(index)
        val indexName = indexMetaData.getIndex.getName
        val aliases: Set[String] = indexMetaData.getAliases.keysIt.asScala.toSet
        (indexName, aliases)
      }
      .toMap
  }

  override def expandIndices(indices: Set[domain.IndexName]): Set[domain.IndexName] = ???
}
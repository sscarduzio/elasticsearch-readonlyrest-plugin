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
package tech.beshu.ror.es.utils

import org.elasticsearch.cluster.metadata.{ComponentTemplate, ComposableIndexTemplate, IndexTemplateMetadata, Metadata}

import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

class ClusterStateMetadataOps(val metadata: Metadata) {

  private lazy val allProjects = metadata.projects().values().asScala

  def allTemplatesMetadata: Map[String, IndexTemplateMetadata] = {
    allProjects.flatMap(_.templates().asScala).toMap
  }

  def allTemplatesV2Metadata: Map[String, ComposableIndexTemplate] = {
    allProjects.flatMap(_.templatesV2().asScala).toMap
  }

  def allComponentTemplatesMetadata: Map[String, ComponentTemplate] = {
    allProjects.flatMap(_.componentTemplates().asScala).toMap
  }
}
object ClusterStateMetadataOps {
  implicit def toOps(metadata: Metadata): ClusterStateMetadataOps = new ClusterStateMetadataOps(metadata)
}

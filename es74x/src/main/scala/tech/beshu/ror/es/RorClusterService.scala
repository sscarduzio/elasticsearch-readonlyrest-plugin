package tech.beshu.ror.es

import tech.beshu.ror.es.RorClusterService._

trait RorClusterService {

  def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid]
  def allIndices: Set[IndexName]
  def allIndicesAndAliases: Map[IndexName, Set[AliasName]]
  def findTemplatesOfIndices(indices: Set[IndexName]): Set[IndexName]
  def getTemplatesWithPatterns: Map[TemplateName, Set[IndexPatten]]
}

object RorClusterService {
  type IndexOrAlias = String
  type IndexName = String
  type AliasName = String
  type IndexUuid = String
  type IndexPatten = String
  type TemplateName = String
}
package tech.beshu.ror.accesscontrol.request

import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances.identityNT

trait RequestInfoShim {
  def extractType: String

  def getExpandedIndices(ixsSet: Set[String]): Set[String] = {
    if(!involvesIndices) {
      throw new IllegalArgumentException("can'g expand indices of a request that does not involve indices: " + extractAction)
    }
    val all = extractAllIndicesAndAliases
      .flatMap { case (indexName, aliases) =>
        aliases + indexName
      }
    MatcherWithWildcardsScalaAdapter.create(ixsSet).filter(all)
  }

  def extractIndexMetadata(index: String): Set[String]

  def extractTaskId: Long

  def extractContentLength: Integer

  def extractContent: String

  def extractMethod: String

  def extractURI: String

  def extractIndices: Set[String]

  def extractSnapshots: Set[String]

  def writeSnapshots(newSnapshots: Set[String]): Unit

  def extractRepositories: Set[String]

  def writeRepositories(newRepositories: Set[String]): Unit

  def extractAction: String

  def extractRequestHeaders: Map[String, String]

  def extractRemoteAddress: String

  def extractLocalAddress: String

  def extractId: String

  def writeIndices(newIndices: Set[String]): Unit

  def writeResponseHeaders(hMap: Map[String, String]): Unit

  def extractAllIndicesAndAliases: Set[(String, Set[String])]

  def extractTemplateIndicesPatterns: Set[String]

  def involvesIndices: Boolean

  def extractIsReadRequest: Boolean

  def extractIsAllowedForDLS: Boolean

  def extractIsCompositeRequest: Boolean

  def writeToThreadContextHeaders(hMap: Map[String, String]): Unit

  def extractHasRemoteClusters: Boolean
}

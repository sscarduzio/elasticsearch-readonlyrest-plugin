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
package tech.beshu.ror.accesscontrol.request

import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances._

trait RequestInfoShim {

  def extractType: String

  def extractIndexMetadata(index: String): Set[String]

  def extractTaskId: Long

  def extractContentLength: Int

  def extractContent: String

  def extractMethod: String

  def extractPath: String

  def extractIndices: Set[String]

  def extractSnapshots: Set[String]

  def extractRepositories: Set[String]

  def extractAction: String

  def extractRequestHeaders: Map[String, String]

  def extractRemoteAddress: String

  def extractLocalAddress: String

  def extractId: String

  def extractAllIndicesAndAliases: Map[String, Set[String]]

  def extractTemplateIndicesPatterns: Set[String]

  def involvesIndices: Boolean

  def extractIsReadRequest: Boolean

  def extractIsAllowedForDLS: Boolean

  def extractIsCompositeRequest: Boolean

  def extractHasRemoteClusters: Boolean

  def writeIndices(newIndices: Set[String])

  def writeResponseHeaders(hMap: Map[String, String])

  def writeSnapshots(newSnapshots: Set[String]): Unit

  def writeRepositories(newRepositories: Set[String]): Unit

  def writeToThreadContextHeaders(hMap: Map[String, String])

  def writeTemplatesOf(indices: Set[String])

  def getExpandedIndices(ixsSet: Set[String]): Set[String] = {
    if (!involvesIndices) throw new IllegalArgumentException("can'g expand indices of a request that does not involve indices: " + extractAction)
    val all = extractAllIndicesAndAliases
      .flatMap { case (index, aliases) =>
        aliases + index
      }
      .toSet
    MatcherWithWildcardsScalaAdapter.create(ixsSet).filter(all)
  }
}

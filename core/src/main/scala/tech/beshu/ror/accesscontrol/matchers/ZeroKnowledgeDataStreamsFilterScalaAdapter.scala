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
package tech.beshu.ror.accesscontrol.matchers

import tech.beshu.ror.accesscontrol.domain.DataStreamName
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeDataStreamsFilterScalaAdapter.CheckResult
import tech.beshu.ror.utils.ZeroKnowledgeIndexFilter
import scala.jdk.CollectionConverters._

class ZeroKnowledgeDataStreamsFilterScalaAdapter(underlying: ZeroKnowledgeIndexFilter)  {
  def check(dataStreams: Set[DataStreamName], matcher: Matcher[DataStreamName]): CheckResult = {
    val processedDataStreams: java.util.Set[String] = scala.collection.mutable.Set.empty[String].asJava
    val result = underlying.alterIndicesIfNecessaryAndCheck(
      dataStreams
        .collect {
          case DataStreamName.Pattern(v) => v.value
          case DataStreamName.Full(v) => v.value
        }
        .asJava,
      Matcher.asMatcherWithWildcards(matcher),
      processedDataStreams.addAll _
    )
    if (result) CheckResult.Ok(processedDataStreams.asScala.flatMap(DataStreamName.fromString).toSet)
    else CheckResult.Failed
  }
}

object ZeroKnowledgeDataStreamsFilterScalaAdapter {
  sealed trait CheckResult
  object CheckResult {
    final case class Ok(processedDataStreams: Set[DataStreamName]) extends CheckResult
    case object Failed extends CheckResult
  }
}

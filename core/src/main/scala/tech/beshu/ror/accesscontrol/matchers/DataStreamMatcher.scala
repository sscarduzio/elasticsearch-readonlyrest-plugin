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
import tech.beshu.ror.utils.Matchable

class DataStreamMatcher[T <: DataStreamName : Matchable](dataStreams: Set[T]) {
  val availableDataStreamsMatcher: Matcher[T] = MatcherWithWildcardsScalaAdapter[T](dataStreams)

  def filterDataStreams(dataStreams: Set[T]): Set[T] = availableDataStreamsMatcher.filter(dataStreams)

  def `match`(value: T): Boolean = availableDataStreamsMatcher.`match`(value)

  def contains(str: String): Boolean = availableDataStreamsMatcher.contains(str)
}

object DataStreamMatcher {
  def create[T <: DataStreamName : Matchable](dataStreams: Set[T]): DataStreamMatcher[T] = {
    new DataStreamMatcher(dataStreams)
  }
}

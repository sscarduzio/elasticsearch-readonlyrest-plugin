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

import cats.implicits._
import com.google.common.base.Strings
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName

// todo: what's this?
class ScalaMatcherWithWildcards[PATTERN: StringTNaturalTransformation](patterns: Set[PATTERN]) {

  private val nt = implicitly[StringTNaturalTransformation[PATTERN]]

  private val matchers = patterns
    .map(p => (p, nt.toAString(p).split("\\*+", -1 /* want empty trailing token if any */).toList))

  def filterWithPatternMatched[T: StringTNaturalTransformation](items: Set[T]): Set[(T, PATTERN)] = {
    items.flatMap(i => `match`(i))
  }

  def filter[T: StringTNaturalTransformation](items: Set[T]): Set[T] = {
    items.flatMap(i => `match`(i).map(_ => i))
  }

  def `match`[T: StringTNaturalTransformation](item: T): Option[(T, PATTERN)] = {
    val itemString = implicitly[StringTNaturalTransformation[T]].toAString(item)
    matchers
      .find { case (_, stringPatterns) => matchPattern(stringPatterns, itemString) }
      .map(p => (item, p._1))
  }

  private def matchPattern(pattern: List[String], line: String): Boolean = {
    if (pattern.isEmpty) return Strings.isNullOrEmpty(line)
    else if (pattern.length === 1) return line === pattern.head
    if (!line.startsWith(pattern.head)) return false
    var idx = pattern.head.length
    var i = 1
    while (i < pattern.length - 1) {
      val patternTok = pattern(i)
      val nextIdx = line.indexOf(patternTok, idx)
      if (nextIdx < 0) return false
      else idx = nextIdx + patternTok.length
      i += 1
      i
    }
    line.endsWith(pattern.last)
  }

}

final case class StringTNaturalTransformation[T](fromString: String => T, toAString: T => String)
object StringTNaturalTransformation {
  object instances {
    implicit val stringIndexNameNT: StringTNaturalTransformation[ClusterIndexName] =
      StringTNaturalTransformation[ClusterIndexName](
        str => ClusterIndexName.fromString(str).getOrElse(throw new IllegalStateException(s"'$str' cannot be converted to IndexName")),
        _.stringify
      )
  }
}
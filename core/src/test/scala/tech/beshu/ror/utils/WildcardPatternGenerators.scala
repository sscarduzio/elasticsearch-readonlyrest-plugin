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
package tech.beshu.ror.utils

import org.scalacheck.Gen

object WildcardPatternGenerators {

  private val regexMetacharacters = "^$|+()[]{}\\-".map(_.toString).toVector
  private val lineTerminators = Vector("\n", "\r", " ", "\u0085")
  private val nonAscii = Vector("é", "日", "😀") // the emoji is a surrogate pair
  val literals: Vector[String] = Vector("a", "b", " ", ".") ++ regexMetacharacters ++ lineTerminators ++ nonAscii
  val alphabet: Vector[String] = literals ++ Vector("*", "?")

  val genLiteral: Gen[String] = genString(literals, 8)

  val genAnyString: Gen[String] = genString(alphabet, 12)

  // Pattern and value use the same symbols, so that the value often contains the pattern pieces.
  val genStarText: Gen[String] = Gen.frequency(9 -> genString(literals, 4), 1 -> genString(literals, 1000))

  def genPatternAndInstance(singleCharWildcard: Boolean, starText: Gen[String] = genStarText): Gen[(String, String)] =
    for {
      pattern <- Gen.frequency(19 -> genString(alphabet, 10), 1 -> genString(alphabet, 60))
      value <- Gen
        .sequence[List[String], String](pattern.toList.map {
          case '*'                       => starText
          case '?' if singleCharWildcard => Gen.oneOf(literals).map(_.take(1))
          case c                         => Gen.const(c.toString)
        })
        .map(_.mkString)
    } yield (pattern, value)

  private def genString(symbols: Vector[String], maxLength: Int): Gen[String] =
    Gen.choose(0, maxLength).flatMap(Gen.listOfN(_, Gen.oneOf(symbols))).map(_.mkString)

}

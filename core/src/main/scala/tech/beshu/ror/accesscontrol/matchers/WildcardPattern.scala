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

import scala.annotation.tailrec

// A glob without regex. `*` matches any characters. With `singleCharWildcard`, `?` matches one character.
// Every other character matches only itself, case-sensitively.
// The value can come from the request, so matching does not backtrack: it finds each text piece between two `*`
// at its first position, from left to right. The time is linear in the value length.
final class WildcardPattern(pattern: String, singleCharWildcard: Boolean) {

  import WildcardPattern.Piece

  private val pieces: Array[Piece] =
    pattern.split("\\*", -1).map(new Piece(_, singleCharWildcard))
  private val prefix: Piece = pieces.head
  private val suffix: Piece = pieces.last
  private val infixes: Array[Piece] = pieces.slice(1, pieces.length - 1)

  def matches(value: String): Boolean =
    if (pieces.length == 1) {
      value.length == prefix.length && prefix.isAt(value, 0)
    } else {
      val end = value.length - suffix.length
      prefix.length <= end && prefix.isAt(value, 0) && suffix.isAt(value, end) &&
      infixesInOrder(value, 0, prefix.length, end)
    }

  @tailrec
  private def infixesInOrder(value: String, infixIndex: Int, from: Int, end: Int): Boolean =
    if (infixIndex == infixes.length) true
    else {
      val infix = infixes(infixIndex)
      val index = infix.firstIndexIn(value, from, end - infix.length)
      index >= 0 && infixesInOrder(value, infixIndex + 1, index + infix.length, end)
    }

}

object WildcardPattern {

  // Text between two `*`.
  private final class Piece(text: String, singleCharWildcard: Boolean) {

    val length: Int = text.length

    private val hasSingleCharWildcard = singleCharWildcard && text.indexOf('?') >= 0

    def isAt(value: String, offset: Int): Boolean =
      if (!hasSingleCharWildcard) value.startsWith(text, offset)
      else {
        var i = 0
        while (i < length && (text.charAt(i) == '?' || text.charAt(i) == value.charAt(offset + i))) i += 1
        i == length
      }

    // The first index in [from, last] where the piece is, or -1.
    def firstIndexIn(value: String, from: Int, last: Int): Int = {
      val index =
        if (!hasSingleCharWildcard) value.indexOf(text, from)
        else {
          var i = from
          while (i <= last && !isAt(value, i)) i += 1
          i
        }
      if (index <= last) index else -1
    }

  }

}

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

class ToKibanaIndexPatternDateTimeFormatter(pattern: String) {

  private val patternLetters = "GyYuUQqMLlwWdDFgEecabBhHKkksSzZXVO"

  private case class ParseState(inQuotedLiteral: Boolean,
                                output: StringBuilder,
                                inDateSegment: Boolean)

  def kibanaIndexPattern: String = {

    val finalState = pattern.zipWithIndex.foldLeft(
      ParseState(inQuotedLiteral = false, output = new StringBuilder, inDateSegment = false)
    ) { case (state@ParseState(inQuotedLiteral, output, inDateSegment), (ch, idx)) =>

      // 1) Escaped quote: '' -> literal '
      if (!inQuotedLiteral && ch == '\'' && idx + 1 < pattern.length && pattern(idx + 1) == '\'') {
        output.append('\'')
        state.copy(inDateSegment = false)
      }

      // 2) Toggle quote state
      else if (ch == '\'') {
        state.copy(inQuotedLiteral = !inQuotedLiteral, inDateSegment = false)
      }

      // 3) Inside quoted literal => append literally
      else if (inQuotedLiteral) {
        output.append(ch)
        state.copy(inDateSegment = false)
      }

      // 4) Date letter => start/continue date segment
      else if (patternLetters.contains(ch)) {
        if (!inDateSegment) output.append('*')
        state.copy(inDateSegment = true)
      }

      // 5) Separator inside date segment => skip it (so the whole segment collapses to one *)
      else if (inDateSegment) {
        state // do not append separator, stay in date segment
      }

      // 6) Normal literal outside date => append
      else {
        output.append(ch)
        state.copy(inDateSegment = false)
      }
    }

    finalState.output.toString
  }
}
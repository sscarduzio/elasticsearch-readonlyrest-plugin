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

import tech.beshu.ror.accesscontrol.domain.IndexPattern

import scala.annotation.tailrec

object KibanaIndexNamePattern {

  import Token.*

  def fromDateTimeIndexNamePattern(dateTimeIndexNamePattern: String): IndexPattern =
    render(collapse(tokenize(dateTimeIndexNamePattern.toList)))

  @tailrec
  private def tokenize(chars: List[Char], acc: List[Token] = Nil): List[Token] = chars match {
    case Nil => acc.reverse
    case '\'' :: '\'' :: rest =>
      tokenize(rest, QuotedText("'") :: acc)
    case '\'' :: rest =>
      val (quoted, remaining) = rest.span(_ != '\'')
      tokenize(remaining.drop(1), QuotedText(quoted.mkString) :: acc)
    case c :: rest if dateLetters(c) =>
      tokenize(rest, DatePart :: acc)
    case c :: rest =>
      tokenize(rest, Literal(c) :: acc)
  }

  @tailrec
  private def collapse(tokens: List[Token], acc: List[Token] = Nil, inDateSegment: Boolean = false): List[Token] = tokens match {
    case Nil =>
      acc.reverse
    case DatePart :: rest if inDateSegment =>
      collapse(rest, acc, inDateSegment = true)
    case DatePart :: rest =>
      collapse(rest, DatePart :: acc, inDateSegment = true)
    case Literal(ch) :: rest if inDateSegment =>
      collapse(rest, acc, inDateSegment = true)
    case head :: rest =>
      collapse(rest, head :: acc, inDateSegment = false)
  }

  private def render(tokens: List[Token]): String =
    tokens
      .map {
        case QuotedText(text) => text
        case DatePart => "*"
        case Literal(ch) => ch.toString
      }
      .mkString

  private val dateLetters: Set[Char] = "GyYuUQqMLlwWdDFgEecabBhHKkksSzZXVO".toSet

  private sealed trait Token

  private object Token {
    case class QuotedText(text: String) extends Token

    case object DatePart extends Token

    case class Literal(ch: Char) extends Token
  }

  import Token.*

}

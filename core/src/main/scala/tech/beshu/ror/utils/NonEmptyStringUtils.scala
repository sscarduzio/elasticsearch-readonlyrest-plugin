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

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString

import scala.annotation.tailrec
import scala.language.implicitConversions

object NonEmptyStringUtils {

  import Token.*

  extension (nes: NonEmptyString)
    def replaceDateTimePatternWithWildcard: Either[DateTimePatternReplacementError, NonEmptyString] = {
      val tokenized = tokenize(nes.toNel)
      val collapsed = collapse(tokenized)
      render(collapsed)
    }

  private def tokenize(chars: NonEmptyList[Char]): NonEmptyList[Token] = {

    def nextToken(chars: NonEmptyList[Char]): (Token, List[Char]) =
      chars match {
        case NonEmptyList('\'', '\'' :: rest) =>
          (Empty, rest)
        case NonEmptyList('\'', rest) =>
          val (quoted, remaining) = rest.span(_ != '\'')
          NonEmptyList.fromList(quoted) match {
            case None =>
              (IncompleteStringLiteral, remaining.drop(1))
            case Some(nonEmptyQuoted) =>
              (QuotedText(nonEmptyQuoted.toNonEmptyString), remaining.drop(1))
          }
        case NonEmptyList(c, rest) if dateLetters(c) =>
          (DatePart, rest)
        case NonEmptyList(c, rest) =>
          (Literal(c), rest)
      }

    @tailrec
    def loop(chars: List[Char], acc: NonEmptyList[Token]): NonEmptyList[Token] = {
      chars match {
        case Nil =>
          acc.reverse
        case c :: rest =>
          val (token, remaining) = nextToken(NonEmptyList(c, rest))
          loop(remaining, acc.prepend(token))
      }
    }

    val (firstToken, rest) = nextToken(chars)
    loop(rest, NonEmptyList.one(firstToken))
  }

  private def collapse(tokens: NonEmptyList[Token]): NonEmptyList[Token] = {

    def nextToken(token: Token, inDateSegment: Boolean): (Option[Token], Boolean) =
      token match {
        case DatePart if inDateSegment =>
          (None, true)
        case DatePart =>
          (Some(DatePart), true)
        case Literal(_) if inDateSegment =>
          (None, true)
        case other =>
          (Some(other), false)
      }

    @tailrec
    def loop(remaining: List[Token], acc: NonEmptyList[Token], inDateSegment: Boolean): NonEmptyList[Token] =
      remaining match {
        case Nil => acc.reverse
        case head :: rest =>
          val (maybeToken, nextInDateSegment) = nextToken(head, inDateSegment)
          val newAcc = maybeToken.fold(acc)(acc.prepend)
          loop(rest, newAcc, nextInDateSegment)
      }

    val head = tokens.head
    val tail = tokens.tail
    val (firstTokenOpt, inDateSegment) = nextToken(head, inDateSegment = false)
    val acc = firstTokenOpt.fold(NonEmptyList.one(head))(NonEmptyList.one)
    loop(tail, acc, inDateSegment)
  }

  private def render(tokens: NonEmptyList[Token]): Either[DateTimePatternReplacementError, NonEmptyString] = {
    val resolvedPattern = tokens.toList.foldLeft(Right(""): Either[DateTimePatternReplacementError, String]) { (acc, token) =>
      for {
        s <- acc
        t <- token match {
          case QuotedText(text) => Right(text.value)
          case DatePart => Right("*")
          case Literal(ch) => Right(ch.toString)
          case Empty => Right("")
          case IncompleteStringLiteral => Left(DateTimePatternReplacementError.IncompleteStringLiteralPresent)
        }
      } yield s + t
    }
    resolvedPattern.flatMap { str =>
      NonEmptyString.from(str).toOption match {
        case None =>
          Left(DateTimePatternReplacementError.PatternResolvingResultsInEmptyString)
        case Some(nonEmptyPattern) if nonEmptyPattern.value.trim.isEmpty =>
          Left(DateTimePatternReplacementError.PatternResolvingResultsInEmptyString)
        case Some(nonEmptyPattern) =>
          Right(nonEmptyPattern)
      }
    }
  }

  private val dateLetters: Set[Char] = "GyYuUQqMLlwWdDFgEecabBhHKkksSzZXVO".toSet

  extension (nes: NonEmptyString)
    private def toNel: NonEmptyList[Char] =
      NonEmptyList.fromListUnsafe(nes.value.toList)

  extension (nel: NonEmptyList[Char])
    private def toNonEmptyString: NonEmptyString =
      NonEmptyString.unsafeFrom(nel.toList.mkString)

  private sealed trait Token

  private object Token {
    case class QuotedText(text: NonEmptyString) extends Token

    case object DatePart extends Token

    case class Literal(ch: Char) extends Token

    case object Empty extends Token

    case object IncompleteStringLiteral extends Token
  }

  sealed trait DateTimePatternReplacementError

  object DateTimePatternReplacementError {
    case object IncompleteStringLiteralPresent extends DateTimePatternReplacementError

    case object PatternResolvingResultsInEmptyString extends DateTimePatternReplacementError
  }

}

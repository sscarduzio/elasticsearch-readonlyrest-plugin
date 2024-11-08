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
package tech.beshu.ror.accesscontrol.blocks.variables

import cats.data.NonEmptyList
import cats.implicits.*
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.variables.Tokenizer.Token.TokenWithVariable

object Tokenizer {

  def tokenize(text: NonEmptyString): NonEmptyList[Token] = {
    val init: (Vector[Token], TokenizerState) = (Vector.empty[Token], TokenizerState.ReadingConst(""))
    val (foundTokens, lastState) = text.value.foldLeft(init) {
      case ((tokens, TokenizerState.ReadingConst(accumulator)), char) =>
        if(specialChars.contains(char)) {
          (tokens, TokenizerState.PossiblyReadingVar(accumulator, char))
        } else if (char == transformationChar && accumulator == "" && tokens.lastOption.exists(canBeTransformed)) {
          val lastToken = tokens.last match {
            case token: TokenWithVariable => token
            case _:Token.Text => throw new IllegalStateException("Token should be a variable token")
          }
          (tokens, TokenizerState.PossiblyReadingVarTransformation(lastToken, transformationChar))
        } else {
          (tokens, TokenizerState.ReadingConst(accumulator + char))
        }
      case ((tokens, state@TokenizerState.PossiblyReadingVar(accumulator, specialChar)), char) =>
        char match {
          case '{' =>
            val newTokens = if (accumulator.nonEmpty) tokens :+ Token.Text(accumulator) else tokens
            (newTokens, TokenizerState.ReadingVar("", specialChar, None))
          case _ if specialChars.contains(char) =>
            (tokens, TokenizerState.PossiblyReadingVar(accumulator + specialChar, char))
          case other =>
            state.withAdditionalChar(char) match {
              case Some(newState) =>
                (tokens, newState)
              case None =>
                (tokens, TokenizerState.ReadingConst(s"$accumulator$specialChar$other"))
            }
        }
      case ((tokens, state@TokenizerState.PossiblyReadingVarWithKeyword(accumulator, specialChar, keywordPart)), char) =>
        char match {
          case '{' =>
            keywords.find(_.name === keywordPart) match {
              case Some(keyword) =>
                val newTokens = if (accumulator.nonEmpty) tokens :+ Token.Text(accumulator) else tokens
                (newTokens, TokenizerState.ReadingVar("", specialChar, Some(keyword)))
              case None =>
                (tokens, TokenizerState.ReadingConst(s"$accumulator$specialChar$keywordPart{"))
            }
          case _ =>
            state.withAdditionalChar(char) match {
              case Some(newState) =>
                (tokens, newState)
              case None =>
                (tokens, TokenizerState.ReadingConst(s"$accumulator$specialChar$keywordPart$char"))
            }
        }
      case ((tokens, TokenizerState.ReadingVar(accumulator, specialChar, keyword)), char) =>
        char match {
          case '}' =>
            val placeholder = keyword match {
              case None =>
                Token.Placeholder(accumulator, s"$specialChar{$accumulator}", None)
              case Some(k: Keyword.Explodable.type) =>
                Token.ExplodablePlaceholder(accumulator, s"$specialChar${k.name}{$accumulator}", None)
            }
            (tokens :+ placeholder, TokenizerState.ReadingConst(""))
          case other =>
            (tokens, TokenizerState.ReadingVar(accumulator + other, specialChar, keyword))
        }
      case ((tokens, TokenizerState.PossiblyReadingVarTransformation(lastToken, specialChar)), char) =>
        char match {
          case '{' =>
            (tokens, TokenizerState.ReadingVarTransformation(lastToken, "", specialChar))
          case other =>
            (tokens, TokenizerState.ReadingConst(s"$specialChar$other"))
        }
      case ((tokens, TokenizerState.ReadingVarTransformation(lastToken, accumulator, specialChar)), char) =>
        char match {
          case '}' if !accumulator.lastOption.contains('\\') =>
            val transformation = Token.Transformation(accumulator, s"$specialChar{$accumulator}")
            val token = lastToken match {
              case t:Token.Placeholder => t.copy(transformation = Some(transformation))
              case t:Token.ExplodablePlaceholder => t.copy(transformation = Some(transformation))
            }
            (tokens.dropRight(1) :+ token , TokenizerState.ReadingConst(""))
          case _ =>
            (tokens, TokenizerState.ReadingVarTransformation(lastToken, accumulator + char, specialChar))
        }
    }

    val lastToken = lastState match {
      case TokenizerState.ReadingConst("") =>
        None
      case TokenizerState.ReadingConst(accumulator) =>
        Some(Token.Text(accumulator))
      case TokenizerState.PossiblyReadingVar(constAccumulator, specialChar) =>
        Some(Token.Text(constAccumulator + specialChar))
      case TokenizerState.PossiblyReadingVarWithKeyword(constAccumulator, specialChar, keywordPart) =>
        Some(Token.Text(constAccumulator + specialChar + keywordPart))
      case TokenizerState.ReadingVar(accumulator, specialChar, None) =>
        Some(Token.Text(s"$specialChar{$accumulator"))
      case TokenizerState.ReadingVar(accumulator, specialChar, Some(keyword)) =>
        Some(Token.Text(s"$specialChar${keyword.name}{$accumulator"))
      case TokenizerState.PossiblyReadingVarTransformation(_,specialChar) =>
        Some(Token.Text(specialChar.toString))
      case TokenizerState.ReadingVarTransformation(_, accumulator, specialChar) =>
        Some(Token.Text(s"$specialChar{$accumulator"))
    }

    NonEmptyList.fromFoldable(foundTokens) match {
      case Some(nel) =>
        lastToken.map(nel.append).getOrElse(nel)
      case None =>
        NonEmptyList.one(lastToken.getOrElse(throw new IllegalStateException("Last token cannot be empty string")))
    }
  }

  private def canBeTransformed(token: Token): Boolean = {
    token match {
      case _:Token.Text => false
      case Token.Placeholder(_, _, transformation) => transformation.isEmpty
      case Token.ExplodablePlaceholder(_, _, transformation) => transformation.isEmpty
    }
  }

  sealed trait Token
  object Token {
    sealed trait TokenWithVariable extends Token
    final case class Text(value: String) extends Token
    final case class Placeholder(name: String,
                                 rawValue: String,
                                 transformation: Option[Transformation]) extends TokenWithVariable
    final case class ExplodablePlaceholder(name: String,
                                           rawValue: String,
                                           transformation: Option[Transformation]) extends TokenWithVariable
    final case class Transformation(name: String, rawValue: String)
  }

  private sealed trait TokenizerState
  private object TokenizerState {
    final case class ReadingConst(accumulator: String) extends TokenizerState
    final case class PossiblyReadingVar(constAccumulator: String, specialChar: Char) extends TokenizerState {
      def withAdditionalChar(c: Char): Option[PossiblyReadingVarWithKeyword] = {
        keywords.find(_.name.startsWith(c.toString)).map(_ => PossiblyReadingVarWithKeyword(constAccumulator, specialChar, c.toString))
      }
    }
    final case class PossiblyReadingVarWithKeyword(constAccumulator: String, specialChar: Char, keywordPart: String) extends TokenizerState {
      def withAdditionalChar(c: Char): Option[PossiblyReadingVarWithKeyword] = {
        val newKeywordPart = keywordPart + c
        keywords.find(_.name.startsWith(newKeywordPart)).map(_ => this.copy(keywordPart = newKeywordPart))
      }
    }
    final case class ReadingVar(accumulator: String, specialChar: Char, keyword: Option[Keyword]) extends TokenizerState
    final case class PossiblyReadingVarTransformation(lastToken: TokenWithVariable, specialChar: Char) extends TokenizerState
    final case class ReadingVarTransformation(lastToken: TokenWithVariable, accumulator: String, specialChar: Char) extends TokenizerState
  }

  private sealed abstract class Keyword(val name: String)
  private object Keyword {
    case object Explodable extends Keyword("explode")
  }

  private val specialChars: Set[Char] = Set('@', '$')
  private val transformationChar: Char = '#'
  private val keywords: Set[Keyword] = Set(Keyword.Explodable)
}
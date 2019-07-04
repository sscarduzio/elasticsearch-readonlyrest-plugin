package tech.beshu.ror.acl.blocks.variables

import cats.implicits._

import scala.language.postfixOps

object Tokenizer {

  def tokenize(text: String): List[Token] = {
    val init: (Vector[Token], TokenizerState) = (Vector.empty[Token], TokenizerState.ReadingConst(""))
    val (lastTokens, lastState) = text.foldLeft(init) {
      case ((tokens, TokenizerState.ReadingConst(accumulator)), char) =>
        if(specialChars.contains(char)) {
          (tokens, TokenizerState.PossiblyReadingVar(accumulator, char))
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
              case None => Token.Placeholder(accumulator, s"$specialChar{$accumulator}")
              case Some(k@Keyword.Explodable) => Token.ExplodablePlaceholder(accumulator, s"$specialChar${k.name}{$accumulator}")
            }
            (tokens :+ placeholder, TokenizerState.ReadingConst(""))
          case other =>
            (tokens, TokenizerState.ReadingVar(accumulator + other, specialChar, keyword))
        }
    }
    lastTokens ++ (lastState match {
      case TokenizerState.ReadingConst("") => Nil
      case TokenizerState.ReadingConst(accumulator) => Token.Text(accumulator) :: Nil
      case TokenizerState.PossiblyReadingVar(constAccumulator, specialChar) => Token.Text(constAccumulator + specialChar) :: Nil
      case TokenizerState.PossiblyReadingVarWithKeyword(constAccumulator, specialChar, keywordPart) => Token.Text(constAccumulator + specialChar + keywordPart) :: Nil
      case TokenizerState.ReadingVar(accumulator, specialChar, None) => Token.Text(s"$specialChar{$accumulator") :: Nil
      case TokenizerState.ReadingVar(accumulator, specialChar, Some(keyword)) => Token.Text(s"$specialChar${keyword.name}{$accumulator") :: Nil
    }) toList
  }

  sealed trait Token
  object Token {
    final case class Text(value: String) extends Token
    final case class Placeholder(name: String, rawValue: String) extends Token
    final case class ExplodablePlaceholder(name: String, rawValue: String) extends Token
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
  }

  private sealed abstract class Keyword(val name: String)
  private object Keyword {
    case object Explodable extends Keyword("explode")
  }

  private val specialChars: Set[Char] = Set('@', '$')
  private val keywords: Set[Keyword] = Set(Keyword.Explodable)
}
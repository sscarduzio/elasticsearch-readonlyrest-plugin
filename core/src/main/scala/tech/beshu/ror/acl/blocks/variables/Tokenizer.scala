package tech.beshu.ror.acl.blocks.variables

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
      case ((tokens, TokenizerState.PossiblyReadingVar(accumulator, specialChar)), char) =>
        char match {
          case '{' =>
            val newTokens = if (accumulator.nonEmpty) tokens :+ Token.Text(accumulator) else tokens
            (newTokens, TokenizerState.ReadingVar("", specialChar))
          case _ if specialChars.contains(char) =>
            (tokens, TokenizerState.PossiblyReadingVar(accumulator + specialChar, char))
          case other =>
            (tokens, TokenizerState.ReadingConst(s"$accumulator$specialChar$other"))
        }
      case ((tokens, TokenizerState.ReadingVar(accumulator, specialChar)), char) =>
        char match {
          case '}' =>
            (tokens :+ Token.Placeholder(accumulator, s"$specialChar{$accumulator}"), TokenizerState.ReadingConst(""))
          case other =>
            (tokens, TokenizerState.ReadingVar(accumulator + other, specialChar))
        }
    }
    lastTokens ++ (lastState match {
      case TokenizerState.ReadingConst("") => Nil
      case TokenizerState.ReadingConst(accumulator) => Token.Text(accumulator) :: Nil
      case TokenizerState.PossiblyReadingVar(constAccumulator, specialChar) => Token.Text(constAccumulator + specialChar) :: Nil
      case TokenizerState.ReadingVar(accumulator, specialChar) => Token.Text(s"$specialChar{$accumulator") :: Nil
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
    final case class PossiblyReadingVar(constAccumulator: String, specialChar: Char) extends TokenizerState
    final case class ReadingVar(accumulator: String, specialChar: Char) extends TokenizerState
  }

  private val specialChars: Set[Char] = Set('@', '$')
  private val explodeKeyword: String = "explode"
}
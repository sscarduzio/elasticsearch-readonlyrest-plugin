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
package tech.beshu.ror.accesscontrol.blocks.variables.transformation.parser

private[parser] object Tokenizer {

  def tokenize(text: String): List[Token] = {
    val pState = text.foldLeft(TokenizerState.empty) { (pState, c) =>
      pState.state match {
        case s: State.ReadingText =>
          punctuator(c, pState, s)
            .orElse(quote(c, pState, s))
            .orElse(name(c, pState, s))
            .getOrElse(pState) // todo improvement handle not quoted nonalphanumerics
        case s: State.ReadingQuotedText =>
          c match {
            case '"' if s.isLastCharEscaped =>
              pState.plus(s.withQuote)
            case '"' =>
              pState.plus(s.toToken, State.empty)
            case other =>
              pState.plus(s.withChar(other))
          }
      }
    }

    val lastToken = pState.state match {
      case State.ReadingText("") => None
      case s@State.ReadingText(_) => Some(s.toToken)
      case State.ReadingQuotedText(acc) => Some(Token.Text(s"\"$acc"))
    }

    lastToken match {
      case Some(value) => pState.tokens :+ value
      case None => pState.tokens
    }
  }

  private def punctuator(c: Char, pState: TokenizerState, state: State.ReadingText): Option[TokenizerState] = {
    Token.Punctuator.getFor(c).map { punctuatorToken =>
      val newTokens: List[Token] = if (state != State.empty) {
        List(state.toToken, punctuatorToken)
      } else {
        List(punctuatorToken)
      }
      pState.plus(newTokens, State.empty)
    }
  }

  private def quote(c: Char, pState: TokenizerState, state: State.ReadingText): Option[TokenizerState] = {
    if (c == '"') {
      val newTokens: List[Token] = if (state != State.empty) {
        List(Token.Text(state.acc))
      } else {
        List.empty
      }
      Some(pState.plus(newTokens, State.ReadingQuotedText("")))
    } else {
      None
    }
  }

  private def name(c: Char, pState: TokenizerState, state: State.ReadingText): Option[TokenizerState] = {
    if (isValidForName(c)) {
      Some(pState.plus(state.withChar(c)))
    } else {
      None
    }
  }

  private def isValidForName(c: Char) = {
    c.isDigit || c.isLetter || c == '_'
  }

  private final case class TokenizerState(tokens: List[Token], state: State) {
    def plus(newTokens: List[Token], newState: State): TokenizerState = {
      this.copy(
        tokens = tokens ++ newTokens,
        state = newState
      )
    }
    def plus(token: Token, newState: State): TokenizerState = plus(List(token), newState)

    def plus(newState: State): TokenizerState = {
      this.copy(
        state = newState
      )
    }
  }

  private object TokenizerState {
    val empty: TokenizerState = TokenizerState(tokens = List.empty, state = State.empty)
  }

  private sealed trait State
  private object State {
    val empty: State = ReadingText("")

    final case class ReadingText(acc: String) extends State {
      def toToken: Token = Token.Text(acc)
      def withChar(char: Char): ReadingText = ReadingText(acc + char)
    }

    final case class ReadingQuotedText(acc: String) extends State {
      def toToken: Token = Token.Text(acc)
      def withChar(char: Char): ReadingQuotedText = ReadingQuotedText(acc + char)
      def withQuote: ReadingQuotedText = ReadingQuotedText(acc.stripSuffix(escapeChar.toString).appended('"'))
      def isLastCharEscaped: Boolean = acc.lastOption.contains(escapeChar)

      private val escapeChar = '\\'
    }
  }
}
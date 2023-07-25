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

import cats.implicits._
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.parser.Parser.{Expression, ParsingError}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.parser.Token.Punctuator

private[parser] object Parselet {
  sealed trait PrefixParselet {
    def parse(parser: Parser, tokenType: Token): Either[ParsingError, Expression]
  }

  object PrefixParselet {
    object NameParselet extends PrefixParselet {
      override def parse(parser: Parser, tokenType: Token): Either[ParsingError, Expression] = {
        tokenType match {
          case Token.Text(value) =>
            Right(Expression.Name(value))
          case other =>
            Left(ParsingError(s"Expected name or quoted value but was '${other.show}'"))
        }
      }
    }
  }

  sealed trait InfixParselet {
    def parse(parser: Parser, left: Expression): Either[ParsingError, Expression]
    def getPrecedence: Int
  }

  object InfixParselet {
    object ChainParselet extends InfixParselet {
      override def parse(parser: Parser, left: Expression): Either[ParsingError, Expression] = {
        parser
          .parseExpression(getPrecedence)
          .map(right => Expression.Chain(left, right))
      }

      override def getPrecedence: Int = 1
    }

    object CallParselet extends InfixParselet {
      def parse(parser: Parser, left: Expression): Either[ParsingError, Expression] = {
        if (!parser.consumeIf(Punctuator.RightParen)) {
          parseArgs(parser)
            .map { args =>
              Expression.Call(left, args)
            }
        }
        else {
          Right(Expression.Call(left, List.empty))
        }
      }

      override def getPrecedence: Int = 2

      private def parseArgs(parser: Parser): Either[ParsingError, List[Expression]] = parseArgs(parser, List.empty)

      // todo improvement - stack safety
      private def parseArgs(parser: Parser, expressions: List[Expression]): Either[ParsingError, List[Expression]] = {
        parseArg(parser)
          .flatMap { expression =>
            parser.consumeToken match {
              case Some(Punctuator.Comma) =>
                parseArgs(parser, expressions :+ expression)
              case Some(Punctuator.RightParen) =>
                Right(expressions :+ expression)
              case Some(other) =>
                ParsingError(s"Expected '${Punctuator.Comma.value}' or '${Punctuator.RightParen.value}' but was '${other.show}'").asLeft
              case None =>
                ParsingError(s"Expected '${Punctuator.Comma.value}' or '${Punctuator.RightParen.value}' but was ''").asLeft
            }
          }
      }

      private def parseArg(parser: Parser): Either[ParsingError, Expression] = {
        parser.parseExpression()
      }
    }
  }
}

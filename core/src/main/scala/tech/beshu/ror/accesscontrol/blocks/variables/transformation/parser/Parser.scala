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

import cats.data.NonEmptyList
import cats.implicits._
import monix.execution.atomic.Atomic
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.parser.Parselet.InfixParselet.{CallParselet, ChainParselet}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.parser.Parselet.PrefixParselet.NameParselet
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.parser.Parselet.{InfixParselet, PrefixParselet}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.parser.Parser._

private class Parser private(tokens: NonEmptyList[Token]) {
  private val leftTokens: Atomic[List[Token]] = Atomic(tokens.toList)

  private[parser] def nextToken: Option[Token] = leftTokens.get().headOption

  private[parser] def consumeToken: Option[Token] = leftTokens.getAndTransform(_.drop(1)).headOption

  private[parser] def consumeIf(expected: Token): Boolean = leftTokens.transformAndExtract { tokens =>
    if (tokens.headOption.contains(expected)) {
      (true, tokens.tail)
    } else {
      (false, tokens)
    }
  }

  private[parser] def parseExpression(): Either[ParsingError, Expression] = parseExpression(0)

  private[parser] def parseExpression(precedence: Int): Either[ParsingError, Expression] = {
    for {
      expression <- parsePrefix
      result <- parseInfix(expression, precedence)
    } yield result
  }

  private def parse(): Either[ParsingError, Expression] = {
    parseExpression()
      .flatMap { expression =>
        val left = leftTokens.get()
        if (left.nonEmpty) {
          Left(ParsingError(s"Some tokens left: $left}"))
        } else {
          Right(expression)
        }
      }
  }

  private def prefixParseletFor(tokenType: Token): Option[PrefixParselet] = {
    tokenType match {
      case _: Token.Text => Some(NameParselet)
      case _: Token.Punctuator => None
    }
  }

  private def infixParseletFor(tokenType: Token): Option[InfixParselet] = {
    tokenType match {
      case _: Token.Text => None
      case Token.Punctuator.LeftParen => Some(CallParselet)
      case Token.Punctuator.Dot => Some(ChainParselet)
      case _: Token.Punctuator => None
    }
  }

  private def getPrecedence: Int = {
    nextToken.flatMap(infixParseletFor).map(_.getPrecedence).getOrElse(0)
  }

  private def parsePrefix: Either[ParsingError, Expression] = {
    for {
      token <- consumeToken.toRight(ParsingError("Could not parse expression"))
      parselet <- prefixParseletFor(token).toRight(ParsingError(s"Could not parse expression '${token.show}'"))
      expression <- parselet.parse(this, token)
    } yield expression
  }

  // todo improvement - stack safety
  private def parseInfix(expression: Expression, precedence: Int): Either[ParsingError, Expression] = {
    if (precedence < getPrecedence) {
      for {
        token <- consumeToken.toRight(ParsingError("Could not parse expression"))
        parselet <- infixParseletFor(token).toRight(ParsingError(s"Could not parse expression '${token.show}'"))
        leftExpression <- parselet.parse(this, expression)
        result <- parseInfix(leftExpression, precedence)
      } yield result
    } else {
      Right(expression)
    }
  }
}

private[transformation] object Parser {

  def parse(str: String): Either[ParsingError, Expression] = {
    val tokens = Tokenizer.tokenize(str)
    NonEmptyList.fromList(tokens)
      .toRight(ParsingError("Expression cannot be empty"))
      .flatMap { tokens =>
        new Parser(tokens).parse()
      }
  }

  sealed trait Expression
  object Expression {
    final case class Name(value: String) extends Expression
    final case class Call(name: Expression, args: List[Expression]) extends Expression
    final case class Chain(left: Expression, right: Expression) extends Expression
  }

  final case class ParsingError(message: String)
}


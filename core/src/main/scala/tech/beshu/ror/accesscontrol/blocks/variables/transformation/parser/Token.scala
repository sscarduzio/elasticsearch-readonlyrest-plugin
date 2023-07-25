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

import cats.Show

private[parser] sealed trait Token
private[parser] object Token {
  final case class Text(value: String) extends Token

  abstract class Punctuator(val value: Char) extends Token
  object Punctuator {
    case object LeftParen extends Punctuator('(')
    case object RightParen extends Punctuator(')')
    case object Comma extends Punctuator(',')
    case object Dot extends Punctuator('.')

    private val all: Map[Char, Punctuator] = Set(LeftParen, RightParen, Comma, Dot).map(p => (p.value, p)).toMap

    def getFor(char: Char): Option[Punctuator] = {
      all.get(char)
    }
  }

  implicit val showToken: Show[Token] = Show.show {
    case text: Text => text.value
    case punctuator: Punctuator => punctuator.value.toString
  }
}
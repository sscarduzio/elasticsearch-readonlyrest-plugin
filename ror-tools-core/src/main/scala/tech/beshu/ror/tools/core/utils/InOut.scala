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
package tech.beshu.ror.tools.core.utils

import scala.io.StdIn

trait InOut {
  def print(str: String): Unit

  def println(str: String): Unit

  def printErr(str: String): Unit

  def printlnErr(str: String): Unit

  def readLine(): String
}

object InOut {
  object ConsoleInOut extends InOut {
    override def print(str: String): Unit = Console.out.print(str)

    override def println(str: String): Unit = Console.out.println(str)

    override def printErr(str: String): Unit = Console.err.print(str)

    override def printlnErr(str: String): Unit = Console.err.println(str)

    override def readLine(): String = StdIn.readLine()
  }
}

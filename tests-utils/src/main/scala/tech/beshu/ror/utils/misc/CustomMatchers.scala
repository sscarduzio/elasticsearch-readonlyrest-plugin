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
package tech.beshu.ror.utils.misc

import org.scalatest.matchers._

trait CustomMatchers {

  class SetMayContainOnlyGivenElementsMatcher(elements: Set[String]) extends Matcher[Set[String]] {

    override def apply(setToCheck: Set[String]): MatchResult = {
      MatchResult(
        setToCheck.diff(elements).isEmpty,
        s"Set [${setToCheck.mkString(",")}] contains unexpected elements like [${setToCheck.diff(elements).mkString(",")}]",
        s"Set [${setToCheck.mkString(",")}] contains only elements specified in [${elements.mkString(",")}]"
      )
    }
  }

  def containAtMostElementsFrom(elements: Set[String]) = new SetMayContainOnlyGivenElementsMatcher(elements)
}

object CustomMatchers extends CustomMatchers

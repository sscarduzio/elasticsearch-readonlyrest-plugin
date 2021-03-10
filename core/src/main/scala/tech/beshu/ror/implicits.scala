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
package tech.beshu.ror

import cats.Show
import tech.beshu.ror.accesscontrol.show.LogsShowInstances

object implicits
  extends LogsShowInstances
    with cats.instances.AllInstances
    with cats.syntax.AllSyntax {

  //  override implicit def catsStdShowForSet[T: Show]: Show[Set[T]] = ???
  //    def show(fa: Set[A]): String =
  //      fa.iterator.map(_.show).mkString("Set(", ", ", ")")
  //  }
  //  implicit def customListShow[T: Show]: Show[List[T]] = Show.show(_.map(_.show).mkString(","))
  //  implicit def customSetShow[T: Show]: Show[Set[T]] =


  override implicit def catsStdShowForSet[A](implicit evidence$3: Show[A]): Show[Set[A]] =
    Show.show(_.map(_.show).mkString(","))
}

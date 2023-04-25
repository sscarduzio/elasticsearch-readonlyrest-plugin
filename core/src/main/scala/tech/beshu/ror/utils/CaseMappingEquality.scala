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
package tech.beshu.ror.utils

import cats._
import cats.implicits._

trait CaseMappingEquality[A] extends Show[A] {
  def mapCases: String => String
}

object CaseMappingEquality {
  def apply[A](implicit caseMappingEquality: CaseMappingEquality[A]): CaseMappingEquality[A] = caseMappingEquality

  def summonJava[A](implicit caseMappingEquality: CaseMappingEquality[A]): CaseMappingEqualityJava[A] =
    new CaseMappingEqualityJava[A] {
      override def show(a: A): String = caseMappingEquality.show(a)

      override def mapCases(from: String): String = caseMappingEquality.mapCases(from)
    }

  def instance[A](_show:A => String, _mapCases: String => String): CaseMappingEquality[A] = new CaseMappingEquality[A] {
    override def show(t: A): String = _show(t)

    override def mapCases: String => String = _mapCases
  }

  implicit def orderCaseMappingEquality[A](implicit caseMappingEquality: CaseMappingEquality[A]): Order[A] =
    Order[String]
      .contramap(caseMappingEquality.mapCases)
      .contramap(_.show)

  implicit final class Ops[A](val value:CaseMappingEquality[A]) extends AnyVal {
    def toOrder: Order[A] = orderCaseMappingEquality(value)
  }

  implicit val contravariantCaseMappingEquality: Contravariant[CaseMappingEquality] = {
    new Contravariant[CaseMappingEquality] {
      override def contramap[A, B](fa: CaseMappingEquality[A])(f: B => A): CaseMappingEquality[B] =
        CaseMappingEquality.instance[B](Show(fa).contramap(f).show, fa.mapCases)
    }
  }
}
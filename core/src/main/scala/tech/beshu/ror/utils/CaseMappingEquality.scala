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
import tech.beshu.ror.accesscontrol.domain.{Action, IndexName, RepositoryName, SnapshotName}

import language.implicitConversions

trait CaseMappingEquality[A] {
  def show: Show[A]
  def mapCases: String => String
}

object CaseMappingEquality {
  def apply[A](implicit caseMappingEquality: CaseMappingEquality[A]): CaseMappingEquality[A] = caseMappingEquality
  def summonJava[A](implicit caseMappingEquality: CaseMappingEquality[A]): CaseMappingEqualityJava[A] = new CaseMappingEqualityJava[A] {
    override def show(a: A): String = caseMappingEquality.show.show(a)

    override def mapCases(from: String): String = caseMappingEquality.mapCases(from)
  }

  def instance[A: Show](_mapCases: String => String): CaseMappingEquality[A] = new CaseMappingEquality[A] {
    override def show: Show[A] = implicitly[Show[A]]

    override def mapCases: String => String = _mapCases
  }

  implicit def eqCaseMappingEquality[A](implicit caseMappingEquality: CaseMappingEquality[A]): Eq[A] =
    Eq[String]
      .contramap(caseMappingEquality.mapCases)
      .contramap(_.show)

  implicit def showCaseMappingEquality[A](implicit caseMappingEquality: CaseMappingEquality[A]): Show[A] =
    caseMappingEquality.show

  implicit val contravariantCaseMappingEquality: Contravariant[CaseMappingEquality] = {
    new Contravariant[CaseMappingEquality] {
      override def contramap[A, B](fa: CaseMappingEquality[A])(f: B => A): CaseMappingEquality[B] =
        CaseMappingEquality.instance[B](fa.mapCases)(fa.show.contramap(f))
    }
  }
}
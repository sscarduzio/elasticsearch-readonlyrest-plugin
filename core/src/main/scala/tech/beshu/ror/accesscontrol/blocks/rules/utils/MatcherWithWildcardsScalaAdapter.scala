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
package tech.beshu.ror.accesscontrol.blocks.rules.utils

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.domain.{IndexName, TemplateNamePattern, User}
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

trait Matcher {
  def underlying: MatcherWithWildcards
  def filter[T : StringTNaturalTransformation](items: Set[T]): Set[T]
  def filter[T : StringTNaturalTransformation](remoteClusterAware: Boolean, items: Set[T]): Set[T]
  def `match`[T : StringTNaturalTransformation](value: T): Boolean
  def contains(str: String): Boolean
}

class MatcherWithWildcardsScalaAdapter(override val underlying: MatcherWithWildcards)
  extends Matcher {

  override def filter[T : StringTNaturalTransformation](remoteClusterAware: Boolean, items: Set[T]): Set[T] = {
    val nt = implicitly[StringTNaturalTransformation[T]]
    underlying
      .filter(remoteClusterAware, items.map(nt.toAString(_)).asJava)
      .asScala
      .map(nt.fromString)
      .toSet
  }

  override def filter[T: StringTNaturalTransformation](items: Set[T]): Set[T] = {
    val nt = implicitly[StringTNaturalTransformation[T]]
    underlying
      .filter(items.map(nt.toAString(_)).asJava)
      .asScala
      .map(nt.fromString)
      .toSet
  }

  override def `match`[T: StringTNaturalTransformation](value: T): Boolean = {
    val nt = implicitly[StringTNaturalTransformation[T]]
    underlying.`match`(nt.toAString(value))
  }

  override def contains(str: String): Boolean =
    underlying.getMatchers.contains(str)
}

object MatcherWithWildcardsScalaAdapter {
  def create[T: StringTNaturalTransformation](items: Iterable[T]): Matcher =
    new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(
      items.map(implicitly[StringTNaturalTransformation[T]].toAString).asJava
    ))
}

final case class StringTNaturalTransformation[T](fromString: String => T, toAString: T => String)
object StringTNaturalTransformation {
  object instances {
    implicit val stringUserIdNT: StringTNaturalTransformation[User.Id] =
      StringTNaturalTransformation[User.Id](str => User.Id(NonEmptyString.unsafeFrom(str)), _.value.value)
    implicit val identityNT: StringTNaturalTransformation[String] =
      StringTNaturalTransformation[String](identity, identity)
    implicit val stringIndexNameNT: StringTNaturalTransformation[IndexName] =
      StringTNaturalTransformation[IndexName](str => IndexName(NonEmptyString.unsafeFrom(str)), _.value.value)
    implicit val templateNamePatternNT: StringTNaturalTransformation[TemplateNamePattern] =
      StringTNaturalTransformation[TemplateNamePattern](str => TemplateNamePattern(NonEmptyString.unsafeFrom(str)), _.value.value)
  }
}
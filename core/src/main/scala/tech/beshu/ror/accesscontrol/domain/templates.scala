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
package tech.beshu.ror.accesscontrol.domain

import cats.implicits._
import eu.timepit.refined.auto._
import cats.Eq
import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.matchers.{Matchable, TemplateNamePatternMatcher, UniqueIdentifierGenerator}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

sealed trait Template {
  def name: TemplateName
}
object Template {
  final case class LegacyTemplate(override val name: TemplateName,
                                  patterns: UniqueNonEmptyList[IndexPattern],
                                  aliases: Set[ClusterIndexName])
    extends Template

  final case class IndexTemplate(override val name: TemplateName,
                                 patterns: UniqueNonEmptyList[IndexPattern],
                                 aliases: Set[ClusterIndexName])
    extends Template

  final case class ComponentTemplate(override val name: TemplateName,
                                     aliases: Set[ClusterIndexName])
    extends Template
}

sealed trait TemplateOperation
object TemplateOperation {

  final case class GettingLegacyTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
    extends TemplateOperation

  final case class AddingLegacyTemplate(name: TemplateName,
                                        patterns: UniqueNonEmptyList[IndexPattern],
                                        aliases: Set[ClusterIndexName])
    extends TemplateOperation

  final case class DeletingLegacyTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
    extends TemplateOperation

  final case class GettingIndexTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
    extends TemplateOperation

  final case class AddingIndexTemplate(name: TemplateName,
                                       patterns: UniqueNonEmptyList[IndexPattern],
                                       aliases: Set[ClusterIndexName])
    extends TemplateOperation

  final case class AddingIndexTemplateAndGetAllowedOnes(name: TemplateName,
                                                        patterns: UniqueNonEmptyList[IndexPattern],
                                                        aliases: Set[ClusterIndexName],
                                                        allowedTemplates: List[TemplateNamePattern])
    extends TemplateOperation

  final case class DeletingIndexTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
    extends TemplateOperation

  final case class GettingLegacyAndIndexTemplates(gettingLegacyTemplates: GettingLegacyTemplates,
                                                  gettingIndexTemplates: GettingIndexTemplates)
    extends TemplateOperation

  final case class GettingComponentTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
    extends TemplateOperation

  final case class AddingComponentTemplate(name: TemplateName,
                                           aliases: Set[ClusterIndexName])
    extends TemplateOperation

  final case class DeletingComponentTemplates(namePatterns: NonEmptyList[TemplateNamePattern])
    extends TemplateOperation
}

final case class TemplateName(value: NonEmptyString)
object TemplateName {
  def fromString(value: String): Option[TemplateName] = {
    NonEmptyString.from(value).map(TemplateName.apply).toOption
  }

  implicit val eqTemplateName: Eq[TemplateName] = Eq.fromUniversalEquals
}

final case class TemplateNamePattern(value: NonEmptyString) {
  private lazy val matcher = TemplateNamePatternMatcher.create(Set(this))

  def matches(templateName: TemplateName): Boolean = matcher.`match`(templateName)

}
object TemplateNamePattern {
  implicit val matchableTemplateNamePattern: Matchable[TemplateNamePattern] = Matchable.matchable(_.value.value)
  val wildcard: TemplateNamePattern = TemplateNamePattern("*")

  def fromString(value: String): Option[TemplateNamePattern] = {
    NonEmptyString
      .from(value).toOption
      .map(TemplateNamePattern.apply)
  }

  def from(templateName: TemplateName): TemplateNamePattern = TemplateNamePattern(templateName.value)

  def generateNonExistentBasedOn(templateNamePattern: TemplateNamePattern)
                                (implicit identifierGenerator: UniqueIdentifierGenerator): TemplateNamePattern = {
    val nonexistentTemplateNamePattern = s"${templateNamePattern.value}_ROR_${identifierGenerator.generate(10)}"
    TemplateNamePattern(NonEmptyString.unsafeFrom(nonexistentTemplateNamePattern))
  }

  def findMostGenericTemplateNamePatten(in: NonEmptyList[TemplateNamePattern]): TemplateNamePattern = {
    def allTheSame(letters: List[Char]) = {
      letters.size > 1 && letters.distinct.size == 1
    }

    def minTemplateNameLength() = {
      in.foldLeft(Int.MaxValue) {
        case (minLength, elem) if elem.value.length < minLength => elem.value.length
        case (minLength, _) => minLength
      }
    }

    if (in.size > 1) {
      TemplateNamePattern
        .fromString {
          val minLength = minTemplateNameLength()
          in
            .toList.map(_.value.value.substring(0, minLength).toCharArray)
            .transpose
            .foldLeft((false, new StringBuilder())) {
              case ((false, builder), letters) if allTheSame(letters) =>
                (false, builder.append(letters.head))
              case ((false, builder), _) =>
                (true, builder.append("*"))
              case (acc, _) =>
                acc
            }
            ._2
            .toString
        }
        .getOrElse(throw new IllegalMonitorStateException(s"Cannot find the most generic template name patten in ${in.toList.map(_.show).mkString(",")}"))
    } else {
      in.head
    }
  }

  implicit val eqTemplateName: Eq[TemplateNamePattern] = Eq.fromUniversalEquals
}


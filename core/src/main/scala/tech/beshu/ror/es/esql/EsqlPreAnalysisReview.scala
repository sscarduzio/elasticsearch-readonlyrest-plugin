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
package tech.beshu.ror.es.esql

import cats.data.NonEmptyList

import java.lang.reflect.Modifier
import scala.util.{Success, Try}

sealed trait PreAnalysisField

object PreAnalysisField {

  /** ROR extracts the index references this field carries. */
  case object Handled extends PreAnalysisField

  /** Reviewed and confirmed to carry no index reference the `indices` rule could authorize. */
  case object NotAnIndexSource extends PreAnalysisField

  /** Carries index references ROR cannot authorize yet, so a query that uses it has to be rejected. */
  case object UnsupportedIndexSource extends PreAnalysisField
}

/**
 * ES keeps the indices a query reads in the fields of its pre-analysis result, and has grown a new one per
 * clause it added ([[PreAnalysisField.Handled]] `lookupIndices` for `LOOKUP JOIN`, `linkedIndices` for views).
 * A field nobody reviewed is indistinguishable from an absent one - both leave ROR extracting nothing, the
 * query scanner finding nothing, and the two agreeing that there is nothing to narrow - so the indices it
 * holds reach ES unauthorized. Reviewing the whole field set turns the next such addition into a rejection.
 */
object EsqlPreAnalysisReview {

  /** Stopping at the declaring class would leave a field a superclass carries unreviewed - the very gap this closes. */
  def instanceFieldNamesOf(preAnalysisClass: Class[?]): List[String] = {
    LazyList
      .iterate(Option(preAnalysisClass))(_.flatMap(clazz => Option(clazz.getSuperclass)))
      .takeWhile(_.isDefined)
      .flatten
      .flatMap(_.getDeclaredFields.toList)
      .filterNot(field => Modifier.isStatic(field.getModifiers))
      .map(_.getName)
      .distinct
      .toList
  }

  def unreviewedFieldsIn(
      fieldNames: List[String],
      reviewed: Map[String, PreAnalysisField],
      valueOf: String => Try[AnyRef]
  ): Option[NonEmptyList[String]] = {
    NonEmptyList.fromList {
      fieldNames.filter { name =>
        reviewed.get(name) match {
          case Some(PreAnalysisField.Handled) | Some(PreAnalysisField.NotAnIndexSource) => false
          case Some(PreAnalysisField.UnsupportedIndexSource) | None                     => isUsedBy(valueOf(name))
        }
      }
    }
  }

  private def isUsedBy(value: Try[AnyRef]): Boolean = value match {
    case Success(null)                                                      => false
    case Success(collection: java.util.Collection[?])                       => !collection.isEmpty
    case Success(map: java.util.Map[?, ?])                                  => !map.isEmpty
    case Success(text: String)                                              => !text.isBlank
    case Success(_: java.lang.Boolean | _: java.lang.Number | _: Character) => false
    case Success(_)                                                         => true
    case _                                                                  => true
  }

}

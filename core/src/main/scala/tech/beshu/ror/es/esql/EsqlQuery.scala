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

import cats.Show
import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.implicits.*

final case class EsqlQuery(value: String) extends AnyVal

/**
 * The key matching a table ES reported to the place in the query text it was written at, so [[EsqlQueryScanner]]
 * has to normalize the text exactly the way ES's parser does.
 */
final case class NormalizedIndexList private (value: String) extends AnyVal

object NormalizedIndexList {

  def fromEsReport(reported: String): Option[NormalizedIndexList] =
    Option.when(!reported.isBlank)(NormalizedIndexList(reported))

  def of(indices: NonEmptyList[ClusterIndexName]): NormalizedIndexList =
    NormalizedIndexList(indices.toList.map(_.show).mkString(","))

  private[esql] def normalizedFromQueryText(text: String): NormalizedIndexList = NormalizedIndexList(text)

  implicit val show: Show[NormalizedIndexList] = Show.show(_.value)
}

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
package tech.beshu.ror.es.sql

import tech.beshu.ror.es.sql.SqlPlanReader.SqlPlan

trait SqlPlanReader {

  def planIn(query: String): Either[Throwable, SqlPlan]

}

object SqlPlanReader {

  sealed trait SqlPlan

  object SqlPlan {

    final case class Statement(tableIdentifiers: List[Any]) extends SqlPlan

    final case class Command(underlyingObject: Any) extends SqlPlan

  }

}

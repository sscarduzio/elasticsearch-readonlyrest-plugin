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
package tech.beshu.ror.es

import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.IndexJsonContentService.{ReadError, WriteError}

trait IndexJsonContentService {

  def sourceOf(index: IndexName.Full, id: String): Task[Either[ReadError, Map[String, _]]]

  def saveContent(index: IndexName.Full, id: String, content: Map[String, String]): Task[Either[WriteError, Unit]]
}

object IndexJsonContentService {

  sealed trait ReadError
  case object ContentNotFound extends ReadError
  case object CannotReachContentSource extends ReadError

  sealed trait WriteError
  case object CannotWriteToIndex extends WriteError
}

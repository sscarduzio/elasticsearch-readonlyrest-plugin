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

import io.circe.Json
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.IndexDocumentReader.{ReadError, WriteError}

trait IndexDocumentReader {
  def documentAsJson(index: IndexName.Full, id: String): Task[Either[ReadError, Json]]
  def saveDocumentJson(index: IndexName.Full, id: String, document: Json): Task[Either[WriteError, Unit]]
}

object IndexDocumentReader {

  sealed trait ReadError
  case object IndexNotFound extends ReadError
  case object DocumentNotFound extends ReadError
  case object DocumentUnreachable extends ReadError

  sealed trait WriteError
  case object CannotWriteToIndex extends WriteError
}

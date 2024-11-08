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
package tech.beshu.ror.es.dlsfls

import cats.data.StateT
import cats.implicits.*
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import org.apache.lucene.index.DirectoryReader
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.index.IndexService
import org.elasticsearch.index.shard.IndexSearcherWrapper
import tech.beshu.ror.constants
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsFromHeaderValue

import scala.util.{Failure, Success, Try}

class RoleIndexSearcherWrapper(indexService: IndexService) extends IndexSearcherWrapper with Logging {

  override def wrap(reader: DirectoryReader): DirectoryReader = {
    val threadContext: ThreadContext = indexService.getThreadPool.getThreadContext
    prepareDocumentFieldReader(threadContext)
      .run(reader).get._2
  }

  private def prepareDocumentFieldReader(threadContext: ThreadContext): StateT[Try, DirectoryReader, DirectoryReader] = {
    StateT { reader =>
      Option(threadContext.getHeader(constants.FIELDS_TRANSIENT)) match {
        case Some(fieldsHeader) =>
          fieldsFromHeaderValue(fieldsHeader)
            .flatMap { fields =>
              Try(RorDocumentFieldReader.wrap(reader, fields))
                .recover { case e => throw new IllegalStateException("FLS: Couldn't extract FLS fields from threadContext", e) }
            }
            .map(r => (r, r))
        case None =>
          logger.debug(s"FLS: ${constants.FIELDS_TRANSIENT} not found in threadContext")
          Success((reader, reader))
      }
    }
  }

  private def fieldsFromHeaderValue(value: String) = {
    lazy val failure = Failure(new IllegalStateException("FLS: Couldn't extract FLS fields from threadContext"))
    for {
      nel <- NonEmptyString.from(value) match {
        case Right(nel) => Success(nel)
        case Left(_) =>
          logger.debug("FLS: empty header value")
          failure
      }
      fields <- transientFieldsFromHeaderValue.fromRawValue(nel) match {
        case result@Success(_) => result
        case Failure(ex) =>
          logger.debug(s"FLS: Cannot decode fields from ${constants.FIELDS_TRANSIENT} header value", ex)
          failure
      }
    } yield fields
  }
}

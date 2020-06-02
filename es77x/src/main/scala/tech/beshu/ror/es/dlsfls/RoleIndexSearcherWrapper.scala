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

import java.io.IOException
import java.util.function.{Function => JavaFunction}

import cats.data.StateT
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import org.apache.lucene.index.DirectoryReader
import org.elasticsearch.common.CheckedFunction
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.index.IndexService
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsFromHeaderValue

import scala.util.{Failure, Success, Try}

object RoleIndexSearcherWrapper extends Logging {

  val instance: JavaFunction[IndexService, CheckedFunction[DirectoryReader, DirectoryReader, IOException]] =
    new JavaFunction[IndexService, CheckedFunction[DirectoryReader, DirectoryReader, IOException]] {

      override def apply(indexService: IndexService): CheckedFunction[DirectoryReader, DirectoryReader, IOException] = {
        val threadContext: ThreadContext = indexService.getThreadPool.getThreadContext
        reader: DirectoryReader =>
          prepareDocumentFieldReader(threadContext)
            .run(reader).get._2
      }

      private def prepareDocumentFieldReader(threadContext: ThreadContext): StateT[Try, DirectoryReader, DirectoryReader] = {
        StateT { reader =>
          Option(threadContext.getHeader(Constants.FIELDS_TRANSIENT)) match {
            case Some(fieldsHeader) =>
              fieldsFromHeaderValue(fieldsHeader)
                .flatMap { fields =>
                  Try(DocumentFieldReader.wrap(reader, fields))
                    .recover { case e => throw new IllegalStateException("FLS: Couldn't extract FLS fields from threadContext", e) }
                }
                .map(r => (r, r))
            case None =>
              logger.debug(s"FLS: ${Constants.FIELDS_TRANSIENT} not found in threadContext")
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
              logger.debug(s"FLS: Cannot decode fields from ${Constants.FIELDS_TRANSIENT} header value", ex)
              failure
          }
        } yield fields
      }
    }
}

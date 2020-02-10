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

import cats.data.StateT
import cats.implicits._
import com.google.common.base.Strings
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.search.{BooleanClause, BooleanQuery, ConstantScoreQuery}
import org.elasticsearch.ExceptionsHelper
import org.elasticsearch.common.logging.LoggerMessageFormat
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.common.xcontent.json.JsonXContent
import org.elasticsearch.index.IndexService
import org.elasticsearch.index.shard.{IndexSearcherWrapper, ShardId, ShardUtils}
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsFromHeaderValue
import tech.beshu.ror.utils.FilterTransient

import scala.util.{Failure, Success, Try}

class RoleIndexSearcherWrapper(indexService: IndexService) extends IndexSearcherWrapper with Logging {

  override def wrap(reader: DirectoryReader): DirectoryReader = {
    val threadContext: ThreadContext = indexService.getThreadPool.getThreadContext
    val result = for {
      _ <- prepareDocumentFieldReader(threadContext)
      newReader <- prepareDocumentFilterReader(threadContext, indexService)
    } yield newReader
    result.run(reader).get._2
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

  private def prepareDocumentFilterReader(threadContext: ThreadContext, indexService: IndexService): StateT[Try, DirectoryReader, DirectoryReader] = {
    StateT { reader =>
      Option(threadContext.getHeader(Constants.FILTER_TRANSIENT)) match {
        case Some(filterSerialized) =>
          for {
            filter <- filterFromHeaderValue(filterSerialized)
            shardId <- getShardId(reader)
            newReader <-
            Try(DocumentFilterReader.wrap(reader, createQuery(indexService, shardId, filter)))
              .recover { case e: IOException =>
                logger.error("DLS: Unable to setup document security")
                throw ExceptionsHelper.convertToElastic(e)
              }
          } yield (newReader, newReader)
        case None =>
          logger.debug(s"DLS: ${Constants.FILTER_TRANSIENT} not found in threadContext")
          Success((reader, reader))
      }
    }
  }

  private def filterFromHeaderValue(value: String) = {
    Try {
      Option(FilterTransient.deserialize(value)) match {
        case Some(ft) =>
          val filter = ft.getFilter
          if(!Strings.isNullOrEmpty(filter)) filter
          else throw new IllegalStateException(s"DLS: ${Constants.FILTER_TRANSIENT} present, but contains no value")
        case None =>
          throw new IllegalStateException(s"DLS: ${Constants.FILTER_TRANSIENT} present, but cannot be deserialized")
      }
    }
  }

  private def getShardId(reader: DirectoryReader) = {
    Option(ShardUtils.extractShardId(reader)) match {
      case Some(value) => Success(value)
      case None => Failure(throw new IllegalStateException(LoggerMessageFormat.format("DLS: Couldn't extract shardId from reader [{}]", reader)))
    }
  }

  private def createQuery(indexService: IndexService, shardId: ShardId, filter: String) = {
    val boolQuery = new BooleanQuery.Builder
    boolQuery.setMinimumNumberShouldMatch(1)
    val queryShardContext = indexService.newQueryShardContext(shardId.id, null, null)
    val parser = JsonXContent.jsonXContent.createParser(queryShardContext.getXContentRegistry, filter)
    val queryBuilder = queryShardContext.newParseContext(parser).parseInnerQueryBuilder.get
    val parsedQuery = queryShardContext.toQuery(queryBuilder)
    boolQuery.add(parsedQuery.query, BooleanClause.Occur.SHOULD)
    new ConstantScoreQuery(boolQuery.build)
  }

}

package tech.beshu.ror.es.security

import java.io.IOException
import java.util.function.{Function => JavaFunction}

import com.google.common.base.Strings
import org.apache.logging.log4j.scala.Logging
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.search.{BooleanClause, BooleanQuery, ConstantScoreQuery}
import org.elasticsearch.ExceptionsHelper
import org.elasticsearch.common.CheckedFunction
import org.elasticsearch.common.logging.LoggerMessageFormat
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler
import org.elasticsearch.common.xcontent.json.JsonXContent
import org.elasticsearch.index.IndexService
import org.elasticsearch.index.shard.{ShardId, ShardUtils}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.Constants
import tech.beshu.ror.es.RorInstanceSupplier
import tech.beshu.ror.utils.FilterTransient

object RoleIndexSearcherWrapper extends Logging {

  val instance: JavaFunction[IndexService, CheckedFunction[DirectoryReader, DirectoryReader, IOException]] =
    new JavaFunction[IndexService, CheckedFunction[DirectoryReader, DirectoryReader, IOException]] {

      override def apply(indexService: IndexService): CheckedFunction[DirectoryReader, DirectoryReader, IOException] = {
        val threadPool = indexService.getThreadPool
        reader: DirectoryReader => {
          RorInstanceSupplier.get() match {
            case Some(_) =>
              readerFor(indexService, threadPool, reader)
            case None =>
              logger.debug("Document filtering not available. Return default reader")
              reader
          }
        }
      }

      private def readerFor(indexService: IndexService, threadPool: ThreadPool, reader: DirectoryReader): DirectoryReader = {
        val threadContext: ThreadContext = threadPool.getThreadContext
        // Field level security (FLS)
        val newReader = prepareDocumentFieldReader(threadContext, reader)
        // Document level security (DLS)
        Option(FilterTransient.deserialize(threadContext.getHeader(Constants.FILTER_TRANSIENT))) match {
          case None =>
            logger.debug("filterTransient not found from threadContext.")
            newReader
          case Some(filterTransient) =>
            val shardId = ShardUtils.extractShardId(reader)
            if (shardId == null) throw new IllegalStateException(LoggerMessageFormat.format("Couldn't extract shardId from reader [{}]", reader))
            val filter = filterTransient.getFilter
            if (Strings.isNullOrEmpty(filter)) newReader
            else prepareDocumentFilterReader(indexService, shardId, newReader, filter)
        }
      }

      private def prepareDocumentFieldReader(threadContext: ThreadContext, reader: DirectoryReader) = {
        try {
          val fieldsHeader: String = threadContext.getHeader(Constants.FIELDS_TRANSIENT)
          if (Strings.isNullOrEmpty(fieldsHeader)) reader
          else DocumentFieldReader.wrap(reader, fieldsHeader.split(",").map(_.trim).toSet)
        } catch {
          case e: IOException =>
            throw new IllegalStateException("Couldn't extract FLS fields from threadContext.", e)
        }
      }

      private def prepareDocumentFilterReader(indexService: IndexService, shardId: ShardId, reader: DirectoryReader, filter: String) = {
        try {
          val boolQuery = new BooleanQuery.Builder
          boolQuery.setMinimumNumberShouldMatch(1)
          val queryShardContext = indexService.newQueryShardContext(shardId.id, null, null, null)
          val parser = JsonXContent.jsonXContent.createParser(queryShardContext.getXContentRegistry, LoggingDeprecationHandler.INSTANCE, filter)
          val queryBuilder = queryShardContext.parseInnerQueryBuilder(parser)
          val parsedQuery = queryShardContext.toQuery(queryBuilder)
          boolQuery.add(parsedQuery.query, BooleanClause.Occur.SHOULD)
          DocumentFilterReader.wrap(reader, new ConstantScoreQuery(boolQuery.build))
        } catch {
          case e: IOException =>
            logger.error("Unable to setup document security")
            throw ExceptionsHelper.convertToElastic(e)
        }
      }
    }
}

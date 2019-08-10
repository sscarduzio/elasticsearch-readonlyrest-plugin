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

package tech.beshu.ror.es.security;

import com.google.common.collect.Sets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.logging.LoggerMessageFormat;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.query.ParsedQuery;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.shard.IndexSearcherWrapper;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardUtils;
import org.elasticsearch.threadpool.ThreadPool;
import tech.beshu.ror.Constants;
import tech.beshu.ror.utils.FilterTransient;

import java.io.IOException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RoleIndexSearcherWrapper extends IndexSearcherWrapper {

  private static final Logger logger = LogManager.getLogger(RoleIndexSearcherWrapper.class);

  private final Function<ShardId, QueryShardContext> queryShardContextProvider;
  private final ThreadPool threadPool;

  public RoleIndexSearcherWrapper(IndexService indexService) {
    if (indexService == null) {
      throw new IllegalArgumentException("Please provide an indexService");
    }
    logger.debug("Create new RoleIndexSearcher wrapper, [{}]", indexService.getIndexSettings().getIndex().getName());
    this.queryShardContextProvider = shardId -> indexService.newQueryShardContext(shardId.id(), null, null, null);
    this.threadPool = indexService.getThreadPool();
  }

  @Override
  protected DirectoryReader wrap(DirectoryReader reader) {
    return prepareDocumentFilterReader(prepareDocumentFieldReader(reader));
  }

  private DirectoryReader prepareDocumentFieldReader(DirectoryReader reader) {
    // Field level security (FLS)
    ThreadContext threadContext = threadPool.getThreadContext();
    try {
      String fieldsHeader = threadContext.getHeader(Constants.FIELDS_TRANSIENT);
      if(fieldsHeader != null) {
        if(!fieldsHeader.isEmpty()) {
          Set<String> fields = Sets.newHashSet(fieldsHeader.split(",")).stream().map(String::trim).collect(Collectors.toSet());
          return DocumentFieldReader.wrap(reader, fields);
        } else {
          throw new IllegalStateException("FLS: " + Constants.FIELDS_TRANSIENT + " not found in threadContext");
        }
      } else {
        return reader;
      }
    } catch (IOException e) {
      throw new IllegalStateException("FLS: Couldn't extract FLS fields from threadContext", e);
    }
  }

  private DirectoryReader prepareDocumentFilterReader(DirectoryReader reader) {
    // Document level security (DLS)
    ThreadContext threadContext = threadPool.getThreadContext();
    String filterHeader = threadContext.getHeader(Constants.FILTER_TRANSIENT);
    if(filterHeader != null) {
      FilterTransient filterTransient = FilterTransient.deserialize(filterHeader);
      if (filterTransient == null) {
        throw new IllegalStateException("DLS: " + Constants.FILTER_TRANSIENT + " present, but cannot be deserialized");
      }

      ShardId shardId = ShardUtils.extractShardId(reader);
      if (shardId == null) {
        throw new IllegalStateException(LoggerMessageFormat.format("Couldn't extract shardId from reader [{}]", reader));
      }
      String filter = filterTransient.getFilter();

      if (filter == null || filter.equals("")) {
        throw new IllegalStateException("DLS: " + Constants.FILTER_TRANSIENT + " present, but contains no value");
      }

      try {
        BooleanQuery.Builder boolQuery = new BooleanQuery.Builder();
        boolQuery.setMinimumNumberShouldMatch(1);
        QueryShardContext queryShardContext = this.queryShardContextProvider.apply(shardId);
        XContentParser parser = JsonXContent.jsonXContent.createParser(queryShardContext.getXContentRegistry(), filter);
        QueryBuilder queryBuilder = queryShardContext.parseInnerQueryBuilder(parser);
        ParsedQuery parsedQuery = queryShardContext.toFilter(queryBuilder);
        boolQuery.add(parsedQuery.query(), BooleanClause.Occur.SHOULD);
        return DocumentFilterReader.wrap(reader, new ConstantScoreQuery(boolQuery.build()));
      } catch (IOException e) {
        logger.error("Unable to setup document security");
        throw ExceptionsHelper.convertToElastic(e);
      }
    } else {
      return reader;
    }
  }

}


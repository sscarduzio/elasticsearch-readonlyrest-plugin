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

import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexSearcher;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.logging.LoggerMessageFormat;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.EngineException;
import org.elasticsearch.index.query.ParsedQuery;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.shard.IndexSearcherWrapper;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardUtils;
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.commons.settings.BasicSettings;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.commons.utils.FilterTransient;
import tech.beshu.ror.es.ESContextImpl;

import java.io.IOException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/*
 * @author Datasweet <contact@datasweet.fr>
 */
public class RoleIndexSearcherWrapper extends IndexSearcherWrapper {
  private final LoggerShim logger;
  private final Function<ShardId, QueryShardContext> queryShardContextProvider;
  private final ThreadContext threadContext;
  private final Boolean enabled;

  public RoleIndexSearcherWrapper(IndexService indexService, Settings s, Environment env) throws Exception {
    if (indexService == null) {
      throw new IllegalArgumentException("Please provide an indexService");
    }
    Logger logger = Loggers.getLogger(this.getClass(), getClass().getSimpleName());
    logger.debug("Create new RoleIndexSearcher wrapper, [{}]", indexService.getIndexSettings().getIndex().getName());
    this.queryShardContextProvider = shardId -> indexService.newQueryShardContext(shardId.id(), null, null, null);
    this.threadContext = indexService.getThreadPool().getThreadContext();

    this.logger = ESContextImpl.mkLoggerShim(logger);
    BasicSettings baseSettings = BasicSettings.fromFileObj(this.logger, env.configFile().toAbsolutePath(), s);
    this.enabled = baseSettings.isEnabled();
  }

  @Override
  protected DirectoryReader wrap(DirectoryReader reader) {
    if (!this.enabled) {
      logger.debug("Document filtering not available. Return defaut reader");
      return reader;
    }

    // Field level security (FLS)
    try {
      String fieldsHeader = threadContext.getTransient(Constants.FIELDS_TRANSIENT);
      Set<String> fields = Strings.isNullOrEmpty(fieldsHeader) ?
          null :
          Sets.newHashSet(fieldsHeader.split(",")).stream().map(String::trim).collect(Collectors.toSet());
      if (fields != null) {
        reader = DocumentFieldReader.wrap(reader, fields);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Couldn't extract FLS fields from threadContext.", e);
    }

    // Document level security (DLS)
    FilterTransient filterTransient = FilterTransient.deserialize(threadContext.getTransient(Constants.FILTER_TRANSIENT));
    if (filterTransient == null) {
      logger.trace("filterTransient not found from threadContext.");
      return reader;
    }

    ShardId shardId = ShardUtils.extractShardId(reader);
    if (shardId == null) {
      throw new IllegalStateException(
          LoggerMessageFormat.format("Couldn't extract shardId from reader [{}]", new Object[] { reader }));
    }
    String filter = filterTransient.getFilter();

    if (filter == null || filter.equals("")) {
      return reader;
    }

    try {
      BooleanQuery.Builder boolQuery = new BooleanQuery.Builder();
      boolQuery.setMinimumNumberShouldMatch(1);
      QueryShardContext queryShardContext = this.queryShardContextProvider.apply(shardId);
      XContentParser parser = JsonXContent.jsonXContent.createParser(queryShardContext.getXContentRegistry(), LoggingDeprecationHandler.INSTANCE, filter);
      QueryBuilder queryBuilder = queryShardContext.parseInnerQueryBuilder(parser);
      ParsedQuery parsedQuery = queryShardContext.toFilter(queryBuilder);
      boolQuery.add(parsedQuery.query(), BooleanClause.Occur.SHOULD);
      DirectoryReader wrappedReader = DocumentFilterReader.wrap(reader, new ConstantScoreQuery(boolQuery.build()));
      return wrappedReader;
    } catch (IOException e) {
      this.logger.error("Unable to setup document security");
      throw ExceptionsHelper.convertToElastic(e);
    }
  }

  @Override
  protected IndexSearcher wrap(IndexSearcher indexSearcher) throws EngineException {
    return indexSearcher;
  }

}

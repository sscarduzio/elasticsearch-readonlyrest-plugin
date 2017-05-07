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

package org.elasticsearch.plugin.readonlyrest.es53x.requestcontext;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkShardRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.termvectors.MultiTermVectorsRequest;
import org.elasticsearch.action.termvectors.TermVectorsRequest;
import org.elasticsearch.common.util.ArrayUtils;
import org.elasticsearch.index.Index;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.requestcontext.Transactional;
import org.elasticsearch.plugin.readonlyrest.testutils.ReflecUtils;
import org.reflections.ReflectionUtils;

import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.plugin.readonlyrest.testutils.ReflecUtils.extractStringArrayFromPrivateMethod;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public class RCTransactionalIndices {

  //private static final Logger logger = Loggers.getLogger(RCTransactionalIndices.class);

  // #XXX hacky as hell - needed for bulk request
  private static final Map<String, Set<String>> restLevelIndicesCache = Maps.newHashMap();

  public static Transactional<Set<String>> mkInstance(RequestContextImpl rc, ESContext es) {
    final Logger logger = es.logger(RCTransactionalIndices.class);
    if (!rc.involvesIndices()) {
      return new DummyTXIndices(es);
    }
    return new Transactional<Set<String>>("rc-indices", es) {

      @Override
      public Set<String> initialize() {
        if (!rc.involvesIndices()) {
          return Collections.emptySet();
        }

        String restRequestId = rc.getId().split("-")[0];
        Set<String> initialIndices = restLevelIndicesCache.get(restRequestId);
        if (initialIndices != null && !initialIndices.isEmpty()) {
          logger.debug("Finding cached indices for: " + rc.getId() + " "
                        + rc.getUnderlyingRequest().getClass().getSimpleName()
                        + ": " + Joiner.on(",").join(initialIndices)
          );
          return initialIndices;
        }
        else {
          restLevelIndicesCache.clear();
          logger.debug("Finding indices for: " + rc.getId() + " " + rc.getUnderlyingRequest().getClass().getSimpleName());
          Set<String> indices = findIndices(rc);
          restLevelIndicesCache.put(restRequestId, indices);
          return indices;
        }
      }

      private Set<String> findIndices(RequestContextImpl rc) {

        String[] indices = new String[0];
        ActionRequest ar = rc.getUnderlyingRequest();

        // CompositeIndicesRequests
        if (ar instanceof MultiGetRequest) {
          MultiGetRequest cir = (MultiGetRequest) ar;

          for (MultiGetRequest.Item ir : cir.getItems()) {
            indices = ArrayUtils.concat(indices, ir.indices(), String.class);
          }
        }
        else if (ar instanceof MultiSearchRequest) {
          MultiSearchRequest cir = (MultiSearchRequest) ar;

          for (SearchRequest ir : cir.requests()) {
            indices = ArrayUtils.concat(indices, ir.indices(), String.class);
          }
        }
        else if (ar instanceof MultiTermVectorsRequest) {
          MultiTermVectorsRequest cir = (MultiTermVectorsRequest) ar;

          for (TermVectorsRequest ir : cir.getRequests()) {
            indices = ArrayUtils.concat(indices, ir.indices(), String.class);
          }
        }
        else if (ar instanceof BulkRequest) {
          BulkRequest cir = (BulkRequest) ar;

          for (DocWriteRequest<?> ir : cir.requests()) {
            String[] docIndices = extractStringArrayFromPrivateMethod("indices", ir, es);
            if (docIndices.length == 0) {
              docIndices = extractStringArrayFromPrivateMethod("index", ir, es);
            }
            indices = ArrayUtils.concat(indices, docIndices, String.class);
          }
        }
        else if (ar instanceof IndexRequest) {
          IndexRequest ir = (IndexRequest) ar;
          indices = ir.indices();
        }
        else if (ar instanceof CompositeIndicesRequest) {
          logger.error(
            "Found an instance of CompositeIndicesRequest that could not be handled: report this as a bug immediately! "
              + ar.getClass().getSimpleName());
        }
        else {
          indices = extractStringArrayFromPrivateMethod("indices", ar, es);
          if (indices == null || indices.length == 0) {
            indices = extractStringArrayFromPrivateMethod("index", ar, es);
          }
        }

        if (indices == null) {
          indices = new String[0];
        }

        Set<String> indicesSet = Sets.newHashSet(indices);

        if (logger.isDebugEnabled()) {
          String idxs = String.join(",", indicesSet);
          logger.debug("Discovered indices: " + idxs);
        }

        return indicesSet;
      }

      @Override
      public Set<String> copy(Set<String> initial) {
        return Sets.newHashSet(initial);
      }

      @Override
      public void onCommit(Set<String> newIndices) {
        // Setting indices by reflection..
        newIndices.remove("<no-index>");
        newIndices.remove("");
        ActionRequest actionRequest = rc.getUnderlyingRequest();

        if (newIndices.equals(getInitial())) {
          logger.debug("id: " + rc.getId() + " - Not replacing. Indices are the same. Old:" + get() + " New:" + newIndices);
          return;
        }
        logger.debug("id: " + rc.getId() + " - Replacing indices. Old:" + getInitial() + " New:" + newIndices);

        if (newIndices.size() == 0) {
          throw es.rorException(
            "Attempted to set empty indices list, this would allow full access, therefore this is forbidden." +
              " If this was intended, set '*' as indices.");
        }


        if (actionRequest instanceof BulkShardRequest) {
          BulkShardRequest bsr = (BulkShardRequest) actionRequest;
          String singleIndex = newIndices.iterator().next();
          String uuid = rc.getIndexMetadata(singleIndex).iterator().next();
          AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            @SuppressWarnings("unchecked")
            Set<Field> fields = ReflectionUtils.getAllFields(bsr.shardId().getClass(), ReflectionUtils.withName("index"));
            fields.stream().forEach(f -> {
              f.setAccessible(true);
              try {
                f.set(bsr.shardId(), new Index(singleIndex, uuid));
              } catch (Throwable e) {
                e.printStackTrace();
              }
            });
            return null;
          });
        }
        boolean okSetResult = ReflecUtils.setIndices(actionRequest, Sets.newHashSet("index", "indices"), newIndices, logger);


        if (!okSetResult && actionRequest instanceof IndicesAliasesRequest) {
          IndicesAliasesRequest iar = (IndicesAliasesRequest) actionRequest;
          List<IndicesAliasesRequest.AliasActions> actions = iar.getAliasActions();
          final boolean[] okSubResult = {false};
          actions.forEach(a -> {
            okSubResult[0] &= ReflecUtils.setIndices(a, Sets.newHashSet("index"), newIndices, logger);
          });
          okSetResult &= okSubResult[0];
        }

        if (okSetResult) {
          logger.debug("success changing indices: " + newIndices + " correctly set as " + get());
        }
        else {
          logger.error("Failed to set indices for type " + rc.getUnderlyingRequest().getClass().getSimpleName());
        }
      }


    };
  }

  static class DummyTXIndices extends Transactional<Set<String>> {
    DummyTXIndices(ESContext es) {
      super("rc-indices-dummy", es);
    }

    @Override
    public Set<String> initialize() {
      return Collections.emptySet();
    }

    @Override
    public Set<String> copy(Set<String> initial) {
      return initial;
    }

    @Override
    public void onCommit(Set<String> value) {
    }
  }
}

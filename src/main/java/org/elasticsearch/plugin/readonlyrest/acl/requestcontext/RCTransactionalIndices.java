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

package org.elasticsearch.plugin.readonlyrest.acl.requestcontext;

import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.termvectors.MultiTermVectorsRequest;
import org.elasticsearch.action.termvectors.TermVectorsRequest;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.util.ArrayUtils;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils;

import static org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils.extractStringArrayFromPrivateMethod;

import java.util.List;
import java.util.Set;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public class RCTransactionalIndices {

   private static final Logger logger = Loggers.getLogger(RCTransactionalIndices.class);


  public static Transactional<Set<String>> mkInstance(RequestContext rc) {
    return new Transactional<Set<String>>("rc-indices") {

      @Override
      public Set<String> initialize() {
        if (!rc.involvesIndices()) {
          throw new RCUtils.RRContextException("cannot get indices of a request that doesn't involve indices");
        }

        logger.info("Finding indices for: " + rc.getId());

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
            String[] docIndices = extractStringArrayFromPrivateMethod("indices", ir, logger);
            if (docIndices.length == 0) {
              docIndices = extractStringArrayFromPrivateMethod("index", ir, logger);
            }
            indices = ArrayUtils.concat(indices, docIndices, String.class);
          }
        }
        else if ( ar instanceof IndexRequest){
          IndexRequest ir = (IndexRequest) ar;
          indices = ir.indices();
        }
        else if (ar instanceof CompositeIndicesRequest) {
          logger.error(
            "Found an instance of CompositeIndicesRequest that could not be handled: report this as a bug immediately! "
              + ar.getClass().getSimpleName());
        }
        else {
          indices = extractStringArrayFromPrivateMethod("indices", ar, logger);
          if (indices.length == 0) {
            indices = extractStringArrayFromPrivateMethod("index", ar, logger);
          }
        }

        if (indices == null) {
          indices = new String[0];
        }

        Set<String> indicesSet = org.elasticsearch.common.util.set.Sets.newHashSet(indices);

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
          logger.info("id: " + rc.getId() + " - Not replacing. Indices are the same. Old:" + get() + " New:" + newIndices);
          return;
        }
        logger.info("id: " + rc.getId() + " - Replacing indices. Old:" + getInitial() + " New:" + newIndices);

        if (newIndices.size() == 0) {
          throw new ElasticsearchException(
              "Attempted to set empty indices list, this would allow full access, therefore this is forbidden." +
                  " If this was intended, set '*' as indices.");
        }

        boolean okSetResult  = ReflecUtils.setIndices(rc.getUnderlyingRequest(), newIndices, logger);


        if (!okSetResult && actionRequest instanceof IndicesAliasesRequest) {
          IndicesAliasesRequest iar = (IndicesAliasesRequest) actionRequest;
          List<IndicesAliasesRequest.AliasActions> actions = iar.getAliasActions();
          final boolean[] okSubResult = {false};
          actions.forEach(a -> {
            okSubResult[0] &= ReflecUtils.setIndices(a, newIndices, logger);
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
}

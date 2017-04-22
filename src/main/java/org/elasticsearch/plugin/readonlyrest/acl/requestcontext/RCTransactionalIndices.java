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
import org.elasticsearch.common.util.ArrayUtils;
import org.elasticsearch.plugin.readonlyrest.es53x.ESContext;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils.extractStringArrayFromPrivateMethod;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public class RCTransactionalIndices {

  public static Transactional<Set<String>> mkInstance(RequestContext rc, ESContext context) {
    if(!rc.involvesIndices()){
      return dummy(context);
    }
    return new Transactional<Set<String>>("rc-indices", context) {

      @Override
      public Set<String> initialize() {
        if (!rc.involvesIndices()) {
          return Collections.emptySet();
        }

        rc.getLogger().info("Finding indices for: " + rc.getId() + " " + rc.getUnderlyingRequest().getClass().getSimpleName());

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
            String[] docIndices = extractStringArrayFromPrivateMethod("indices", ir, rc.getLogger());
            if (docIndices.length == 0) {
              docIndices = extractStringArrayFromPrivateMethod("index", ir, rc.getLogger());
            }
            indices = ArrayUtils.concat(indices, docIndices, String.class);
          }
        }
        else if (ar instanceof IndexRequest) {
          IndexRequest ir = (IndexRequest) ar;
          indices = ir.indices();
        }
        else if (ar instanceof CompositeIndicesRequest) {
          rc.getLogger().error(
            "Found an instance of CompositeIndicesRequest that could not be handled: report this as a bug immediately! "
              + ar.getClass().getSimpleName());
        }
        else {
          indices = extractStringArrayFromPrivateMethod("indices", ar, rc.getLogger());
          if (indices.length == 0) {
            indices = extractStringArrayFromPrivateMethod("index", ar, rc.getLogger());
          }
        }

        if (indices == null) {
          indices = new String[0];
        }

        Set<String> indicesSet = Sets.newHashSet(indices);

        if (rc.getLogger().isDebugEnabled()) {
          String idxs = String.join(",", indicesSet);
          rc.getLogger().debug("Discovered indices: " + idxs);
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
          rc.getLogger().info("id: " + rc.getId() + " - Not replacing. Indices are the same. Old:" + get() + " New:" + newIndices);
          return;
        }
        rc.getLogger().info("id: " + rc.getId() + " - Replacing indices. Old:" + getInitial() + " New:" + newIndices);

        if (newIndices.size() == 0) {
          throw new ElasticsearchException(
            "Attempted to set empty indices list, this would allow full access, therefore this is forbidden." +
              " If this was intended, set '*' as indices.");
        }

        boolean okSetResult = ReflecUtils.setIndices(rc.getUnderlyingRequest(), newIndices, rc.getLogger());


        if (!okSetResult && actionRequest instanceof IndicesAliasesRequest) {
          IndicesAliasesRequest iar = (IndicesAliasesRequest) actionRequest;
          List<IndicesAliasesRequest.AliasActions> actions = iar.getAliasActions();
          final boolean[] okSubResult = {false};
          actions.forEach(a -> {
            okSubResult[0] &= ReflecUtils.setIndices(a, newIndices, rc.getLogger());
          });
          okSetResult &= okSubResult[0];
        }

        if (okSetResult) {
          rc.getLogger().debug("success changing indices: " + newIndices + " correctly set as " + get());
        }
        else {
          rc.getLogger().error("Failed to set indices for type " + rc.getUnderlyingRequest().getClass().getSimpleName());
        }
      }


    };
  }

  private static Transactional<Set<String>> dummy(ESContext context) {
    return new Transactional<Set<String>>("rc-indices-dummy", context) {
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
    };
  }

}

/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest.acl;

import com.carrotsearch.hppc.ObjectLookupContainer;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.util.ArrayUtils;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugin.readonlyrest.SecurityPermissionException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeyRule;
import org.elasticsearch.plugin.readonlyrest.wiring.ThreadRepo;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.threadpool.ThreadPool;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class RequestContext {
  /*
    * A regular expression to match the various representations of "localhost"
    */
  private static final Pattern localhostRe = Pattern.compile("^(127(\\.\\d+){1,3}|[0:]+1)$");
  private static final String LOCALHOST = "127.0.0.1";
  private static MatcherWithWildcards canBypassIndexSecurityMatcher = new MatcherWithWildcards(Sets.newHashSet(
      "cluster:monitor/*"
  ));
  private static MatcherWithWildcards readRequestMatcher = new MatcherWithWildcards(Sets.newHashSet(
      "cluster:monitor/*",
      "indices:admin/mappings/fields/get",
      "indices:admin/exists",
      "indices:admin/mappings/fields/get*",
      "indices:admin/validate/query",
      "indices:data/read/field_stats",
      "indices:data/read/search",
      "indices:data/read/msearch",
      "indices:admin/get",
      "indices:admin/refresh*",
      "indices:data/read/*"
  ));
  private final Logger logger = Loggers.getLogger(getClass());
  private final RestChannel channel;
  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private final String id;
  private final Map<String, String> headers;
  private final ThreadPool threadPool;
  private Set<String> indices = null;
  private String content = null;
  private IndicesService indexService = null;

  private RequestSideEffects sideEffects;

  public RequestContext(RestChannel channel, RestRequest request, String action,
                        ActionRequest actionRequest, IndicesService indicesService, ThreadPool threadPool) {
    this.sideEffects = new RequestSideEffects(this);
    this.channel = channel;
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
    this.indexService = indicesService;
    this.threadPool = threadPool;
    this.id = UUID.randomUUID().toString().replace("-", "");
    final Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    request.headers().forEach(e -> {
      h.put(e.getKey(), e.getValue());
    });
    this.headers = h;
  }

  public void commit() {
    sideEffects.commit();
  }

  public void reset() {
    sideEffects.clear();
  }

  public boolean canBypassIndexSecurity() {
    return canBypassIndexSecurityMatcher.match(action);
  }

  public boolean isReadRequest() {
    return readRequestMatcher.match(action);
  }

  public String getRemoteAddress() {
    String remoteHost = ((InetSocketAddress) request.getRemoteAddress()).getAddress().getHostAddress();
    // Make sure we recognize localhost even when IPV6 is involved
    if (localhostRe.matcher(remoteHost).find()) {
      remoteHost = LOCALHOST;
    }
    return remoteHost;
  }

  public String getContent() {
    if (content == null) {
      try {
        content = request.content().utf8ToString();
      } catch (Exception e) {
        content = "";
      }
    }
    return content;
  }

  public Set<String> getAvailableIndicesAndAliases() {
    final HashSet<String> harvested = new HashSet<>();
    final Iterator<IndexService> i = indexService.iterator();
    AccessController.doPrivileged(
        new PrivilegedAction<Void>() {
          @Override
          public Void run() {
            while (i.hasNext()) {
              IndexService theIndexSvc = i.next();
              harvested.add(theIndexSvc.index().getName());

              // Harvest aliases for this index too
              ObjectLookupContainer<String> aliases = theIndexSvc.getIndexSettings().getIndexMetaData().getAliases().keys();
              Iterator<ObjectCursor<String>> it = aliases.iterator();
              while (it.hasNext()) {
                ObjectCursor<String> c = it.next();
                harvested.add(c.value);
              }
            }
            return null;
          }
        });
    return harvested;
  }

  public String getMethod() {
    return request.method().name();
  }

  public Set<String> getIndices() {
    if (indices != null) {
      return indices;
    }

    final String[][] out = {new String[1]};
    AccessController.doPrivileged(
        new PrivilegedAction<Void>() {
          @Override
          public Void run() {
            String[] indices = new String[0];
            ActionRequest ar = actionRequest;

            if (ar instanceof CompositeIndicesRequest) {
              CompositeIndicesRequest cir = (CompositeIndicesRequest) ar;
              for (IndicesRequest ir : cir.subRequests()) {
                indices = ArrayUtils.concat(indices, ir.indices(), String.class);
              }
            } else {
              try {
                Method m = ar.getClass().getMethod("indices");
                if (m.getReturnType() != String[].class) {
                  out[0] = new String[]{};
                  return null;
                }
                m.setAccessible(true);
                indices = (String[]) m.invoke(ar);
              } catch (SecurityException e) {
                logger.error("Can't get indices for request: " + toString());
                throw new SecurityPermissionException(
                    "Insufficient permissions to extract the indices. Abort! Cause: " + e.getMessage(), e);
              } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                logger.debug("Failed to discover indices associated to this request: " + this);
              }
            }

            if (indices == null) {
              indices = new String[0];
            }

            // De-dup
            HashSet<String> tempSet = new HashSet<>(Arrays.asList(indices));
            indices = tempSet.toArray(new String[tempSet.size()]);

            if (logger.isDebugEnabled()) {
              String idxs = String.join(",", indices);
              logger.debug("Discovered indices: " + idxs);
            }

            out[0] = indices;
            return null;
          }
        }
    );

    indices = org.elasticsearch.common.util.set.Sets.newHashSet(out[0]);

    return indices;
  }

  public void setIndices(final Set<String> newIndices) {
    sideEffects.appendEffect(() -> doSetIndices(newIndices));
  }

  public void doSetIndices(final Set<String> newIndices) {
    newIndices.remove("<no-index>");

    if (newIndices.equals(getIndices())) {
      logger.info("id: " + id + " - Not replacing. Indices are the same. Old:" + getIndices() + " New:" + newIndices);
      return;
    }
    logger.info("id: " + id + " - Replacing indices. Old:" + getIndices() + " New:" + newIndices);

    AccessController.doPrivileged(
        new PrivilegedAction<Void>() {
          @Override
          public Void run() {
            Class<?> c = actionRequest.getClass();
            final List<Throwable> results = Lists.newArrayList();
            results.addAll(setStringArrayInInstance(c, actionRequest, "indices", newIndices));
            if (!results.isEmpty()) {
              IndicesAliasesRequest iar = (IndicesAliasesRequest) actionRequest;
              List<IndicesAliasesRequest.AliasActions> actions = iar.getAliasActions();
              actions.forEach(a -> {
                results.addAll(setStringArrayInInstance(a.getClass(), a, "indices", newIndices));
              });
            }
            if (results.isEmpty()) {
              indices.clear();
              indices.addAll(newIndices);
            } else {
              results.forEach(e -> {
                logger.error("Failed to set indices " + e.toString());
              });
            }
            return null;
          }
        });
  }

  private List<Throwable> setStringArrayInInstance(Class<?> theClass, Object instance, String fieldName, Set<String> injectedStringArray) {
    Class<?> c = theClass;
    final List<Throwable> errors = new ArrayList<>();
    try {
      boolean useSuperClass = true;
      for (Field f : c.getDeclaredFields()) {
        if (fieldName.equals(f.getName())) {
          useSuperClass = false;
        }
      }
      if (useSuperClass) {
        c = c.getSuperclass();
      }
      Field field = c.getDeclaredField(fieldName);
      field.setAccessible(true);
      String[] idxArray = injectedStringArray.toArray(new String[injectedStringArray.size()]);
      field.set(instance, idxArray);
    } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
      errors.add(new SetIndexException(c, id, e));
    }
    return errors;
  }

  public void setResponseHeader(String name, String value) {
    sideEffects.appendEffect(() -> doSetResponseHeader(name, value));
  }

  public void doSetResponseHeader(String name, String value) {
    threadPool.getThreadContext().addResponseHeader(name, value);
  }

  public Map<String, String> getHeaders() {
    return this.headers;
  }

  public String getUri() {
    return request.uri();
  }

  public String getAction() {
    return action;
  }


  @Override
  public String toString() {
    String idxs = Joiner.on(",").skipNulls().join(getIndices());

    Joiner.MapJoiner mapJoiner = Joiner.on(",").withKeyValueSeparator("=");
    String hist = mapJoiner.join(ThreadRepo.history.get());
    String loggedInAs = AuthKeyRule.getBasicAuthUser(getHeaders());
    String content = getContent();
    if (Strings.isNullOrEmpty(content)) {
      content = "<N/A>";
    }
    return "{ id: " + id +
        ", type: " + actionRequest.getClass().getSimpleName() +
        ", user: " + loggedInAs +
        ", action: " + action +
        ", OA:" + getRemoteAddress() +
        ", indices:" + idxs +
        ", M:" + request.method() +
        ", P:" + request.path() +
        ", C:" + (logger.isDebugEnabled() ? content : "<OMITTED, LENGTH=" + getContent().length() + ">") +
        ", Headers:" + request.headers() +
        ", History:" + hist +
        ", Effects:" + sideEffects.size() +
        " }";
  }

  class SetIndexException extends Exception {
    SetIndexException(Class<?> c, String id, Throwable e) {
      super(" Could not set indices to class " + c.getSimpleName() +
          "for req id: " + id + " because: "
          + e.getClass().getSimpleName() + " : " + e.getMessage() +
          (e.getCause() != null ? " caused by: " + e.getCause().getClass().getSimpleName() + " : " + e.getCause().getMessage() : ""));
    }
  }

}

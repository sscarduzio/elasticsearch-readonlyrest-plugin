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

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.ObjectArrays;
import com.google.common.collect.Sets;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.aliases.IndexAliasesService;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugin.readonlyrest.SecurityPermissionException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeyRule;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
  private final ESLogger logger = Loggers.getLogger(getClass());
  private final RestChannel channel;
  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private final String id;
  private Set<String> indices = null;
  private String content = null;
  private IndicesService indexService = null;
  private Map<String, String> headers;

  private RequestSideEffects sideEffects;

  public RequestContext(RestChannel channel, RestRequest request, String action, ActionRequest actionRequest, IndicesService indicesService) {
    this.id = UUID.randomUUID().toString().replace("-", "");
    this.sideEffects = new RequestSideEffects(this);
    this.channel = channel;
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
    this.indexService = indicesService;
    final Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (Map.Entry<String, String> e : request.headers()) {
      h.put(e.getKey(), e.getValue());
    }
    this.headers = h;
  }

  public String getId(){
    return id;
  }

  public void commit() {
    sideEffects.commit();
  }

  public void reset() {
    sideEffects.clear();
  }

  public boolean involvesIndices() {
    return actionRequest instanceof IndicesRequest || actionRequest instanceof CompositeIndicesRequest;
  }

  public boolean isReadRequest() {
    return actionRequest instanceof IndicesRequest ||
        actionRequest instanceof GetRequest ||
        actionRequest instanceof MultiGetRequest;
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
        content = new String(request.content().array());
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
              final IndexAliasesService aliasSvc = theIndexSvc.aliasesService();
              try {
                Field field = aliasSvc.getClass().getDeclaredField("aliases");
                field.setAccessible(true);
                ImmutableOpenMap<String, String> aliases = (ImmutableOpenMap<String, String>) field.get(aliasSvc);
                System.out.printf(aliases.toString());
                for (Object o : aliases.keys().toArray()) {
                  String a = (String) o;
                  harvested.add(a);
                }
                //  harvested.addAll(aliases.keys().toArray(new String[aliases.keys().size()]));
              } catch (NoSuchFieldException e) {
                e.printStackTrace();
              } catch (IllegalAccessException e) {
                e.printStackTrace();
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
                indices = ObjectArrays.concat(indices, ir.indices(), String.class);
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
                throw new SecurityPermissionException("Insufficient permissions to extract the indices. Abort! Cause: " + e.getMessage(), e);
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
              String idxs = Joiner.on(',').skipNulls().join(indices);
              logger.debug("Discovered indices: " + idxs);
            }

            out[0] = indices;
            return null;
          }
        }
    );

    indices = Sets.newHashSet(out[0]);

    return indices;
  }

  public void setIndices(final Set<String> newIndices) {
    sideEffects.appendEffect(new Runnable() {
      @Override
      public void run() {
        doSetIndices(newIndices);
      }
    });
  }

  private void doSetIndices(final Set<String> newIndices) {
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
              for (IndicesAliasesRequest.AliasActions a : actions) {
                results.addAll(setStringArrayInInstance(a.getClass(), a, "indices", newIndices));
              }
            }
            if (results.isEmpty()) {
              indices.clear();
              indices.addAll(newIndices);
            } else {
              for (Throwable e : results) {
                logger.error("Failed to set indices " + e.toString());
              }
            }
            return null;
          }
        });
  }

  public String getAction() {
    return action;
  }

  // #TODO This does not work yet
  public void setResponseHeader(final String name, final String value) {
    sideEffects.appendEffect(new Runnable() {
      @Override
      public void run() {
        doSetResponseHeader(name, value);
      }
    });
  }

  private void doSetResponseHeader(String name, String value) {
    Map<String, String> rh = request.getFromContext("response_headers");
    if (rh == null) {
      rh = new HashMap<>(1);
    }
    rh.put(name, value);
    request.putInContext("response_headers", rh);
  }

  @Override
  public String toString() {
    String idxs = Joiner.on(",").skipNulls().join(getIndices());
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
        ", Effects:" + sideEffects.size() +
        " }";
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public String getUri() {
    return request.uri();
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

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
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.util.ArrayUtils;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugin.readonlyrest.SecurityPermissionException;
import org.elasticsearch.plugin.readonlyrest.wiring.ThreadRepo;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_RED;
import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_RESET;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class RequestContext {
  /*
    * A regular expression to match the various representations of "localhost"
    */
  private static final Pattern localhostRe = Pattern.compile("^(127(\\.\\d+){1,3}|[0:]+1)$");
  private static final String LOCALHOST = "127.0.0.1";
  private final Logger logger = Loggers.getLogger(getClass());
  private final RestChannel channel;
  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private final String id;
  private Set<String> indices = null;
  private String content = null;
  private IndicesService indexService = null;

  public RequestContext(RestChannel channel, RestRequest request, String action,
                        ActionRequest actionRequest, IndicesService indicesService) {
    this.channel = channel;
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
    this.indexService = indicesService;
    this.id = Long.toHexString(System.currentTimeMillis());
  }

  public RequestContext(RestChannel channel, RestRequest request, String action, ActionRequest actionRequest) {
    this.channel = channel;
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
    this.id = Long.toHexString(System.currentTimeMillis());
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
        content = ThreadRepo.request.get().content().utf8ToString();
      } catch (Exception e) {
        content = "<not available>";
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

  public Set<String> getIndices() {
    getAvailableIndicesAndAliases();

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
    AccessController.doPrivileged(
        new PrivilegedAction<Void>() {
          @Override
          public Void run() {
            try {
              Field field = actionRequest.getClass().getDeclaredField("indices");
              field.setAccessible(true);
              String[] idxArray = newIndices.toArray(new String[newIndices.size()]);
              field.set(actionRequest, idxArray);
            } catch (NoSuchFieldException e) {
              logger.error(ANSI_RED + " Could not set indices because: " + e.getCause() + ANSI_RESET);
              e.printStackTrace();
            } catch (IllegalAccessException e) {
              logger.error(ANSI_RED + " Could not set indices because: " + e.getCause() + ANSI_RESET);
              e.printStackTrace();
            }
            indices.clear();
            indices.addAll(newIndices);
            return null;
          }
        });
  }

  public RestChannel getChannel() {
    return channel;
  }

  public RestRequest getRequest() {
    return request;
  }

  public String getAction() {
    return action;
  }

  public ActionRequest getActionRequest() {
    return actionRequest;
  }

  @Override
  public String toString() {
    StringBuilder indicesStringBuilder = new StringBuilder();
    indicesStringBuilder.append("[");
    try {
      for (String i : getIndices()) {
        indicesStringBuilder.append(i).append(' ');
      }
    } catch (Exception e) {
      indicesStringBuilder.append("<CANNOT GET INDICES>");
    }
    String idxs = indicesStringBuilder.toString().trim() + "]";

    Joiner.MapJoiner mapJoiner = Joiner.on(",").withKeyValueSeparator("=");
    String hist = mapJoiner.join(ThreadRepo.history.get());

    return "{ id: " + id +
        ", action: " + action +
        ", OA:" + getRemoteAddress() +
        ", indices:" + idxs +
        ", M:" + request.method() +
        ", P:" + request.path() +
        ", C:" + (logger.isDebugEnabled() ? getContent() : "<OMITTED, LENGTH=" + getContent().length() + ">") +
        ", Headers:" + request.headers() +
        ", History:" + hist +
        " }";
  }

}

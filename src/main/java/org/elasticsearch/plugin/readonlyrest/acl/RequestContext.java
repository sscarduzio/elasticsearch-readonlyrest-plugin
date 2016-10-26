package org.elasticsearch.plugin.readonlyrest.acl;

import com.google.common.base.Joiner;
import com.google.common.collect.ObjectArrays;
import com.google.common.collect.Sets;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.aliases.IndexAliasesService;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugin.readonlyrest.SecurityPermissionException;
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
  private final ESLogger logger = Loggers.getLogger(getClass());
  /*
    * A regular expression to match the various representations of "localhost"
    */
  private final static Pattern localhostRe = Pattern.compile("^(127(\\.\\d+){1,3}|[0:]+1)$");

  private final static String LOCALHOST = "127.0.0.1";

  private final RestChannel channel;
  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private Set<String> indices = null;
  private String content = null;
  private IndicesService indexService = null;

  public RequestContext(RestChannel channel, RestRequest request, String action, ActionRequest actionRequest, IndicesService indicesService) {
    this.channel = channel;
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
    this.indexService = indicesService;
  }

  public RequestContext(RestChannel channel, RestRequest request, String action, ActionRequest actionRequest) {
    this.channel = channel;
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
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
    return "{ action: " + action +
        ", OA:" + getRemoteAddress() +
        ", indices:" + idxs +
        ", M:" + request.method() +
        ", P:" + request.path() +
        ", C:" + getContent() +
        ", Headers:" + request.getHeaders() +
        "}";
  }

}

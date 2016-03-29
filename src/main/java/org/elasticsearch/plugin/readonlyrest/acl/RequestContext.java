package org.elasticsearch.plugin.readonlyrest.acl;

import com.google.common.collect.ObjectArrays;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.SecurityPermissionException;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class RequestContext {
  private final static ESLogger logger = Loggers.getLogger(RequestContext.class);

  private final RestChannel channel;
  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private String[] indices = null;

  public RequestContext(RestChannel channel, RestRequest request, String action, ActionRequest actionRequest) {
    this.channel = channel;
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
  }

  public String[] getIndices() {
    if (indices != null) {
      return indices;
    }
    final String[][] out = {new String[1]};
    try {
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
                // Dedupe indices
                HashSet<String> tempSet = new HashSet<>(Arrays.asList(indices));
                indices = tempSet.toArray(new String[tempSet.size()]);
              } else {
                try {
                  Method m = ar.getClass().getMethod("indices");
                  m.setAccessible(true);
                  indices = (String[]) m.invoke(ar);
                } catch (SecurityException | IllegalArgumentException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                  logger.error("Can't get indices for request: " + toString());
                  throw new SecurityPermissionException("Insufficient permissions to extract the indices. Abort! Cause: " + e.getMessage(), e);
                }
              }
              logger.error("Discovered indices: " + indices[0]);
              out[0] = indices;
              return null;
            }
          }
      );
    } catch (Exception e) {
      logger.warn("Couldn't get any indices associated to this: " + this);
    }
    indices = out[0];
    return out[0];
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
    return request.method() + " " + request.path() + " \n" + new String(request.content().toBytes()) + "\n";
  }

}

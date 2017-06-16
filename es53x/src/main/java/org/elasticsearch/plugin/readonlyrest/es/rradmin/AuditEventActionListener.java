package org.elasticsearch.plugin.readonlyrest.es.rradmin;

import org.elasticsearch.client.Client;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;

/**
 * Created by sscarduzio on 03/06/2017.
 */
public abstract class AuditEventActionListener {
  private final Client client;
  private final ESContext context;

  public AuditEventActionListener(ESContext context, Client client) {
    this.client = client;
    this.context = context;
  }

  abstract void onACLResult(RequestContext rc, BlockExitResult result);
}

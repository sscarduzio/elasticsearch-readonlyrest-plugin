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

package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugin.readonlyrest.acl.ACL;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.wiring.ThreadRepo;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;

/**
 * Created by sscarduzio on 19/12/2015.
 */
@Singleton
public class IndexLevelActionFilter extends ActionFilter.Simple {
  private final ThreadPool threadPool;
  private IndicesService indicesService;
  private ACL acl;
  private ConfigurationHelper conf;

  @Inject
  public IndexLevelActionFilter(Settings settings, ACL acl, ConfigurationHelper conf,
                                IndicesService indicesService, ThreadPool threadPool) {
    super(settings);
    this.conf = conf;
    this.indicesService = indicesService;
    this.threadPool = threadPool;

    logger.info("Readonly REST plugin was loaded...");

    if (!conf.enabled) {
      logger.info("Readonly REST plugin is disabled!");
      return;
    }

    logger.info("Readonly REST plugin is enabled. Yay, ponies!");
    this.acl = acl;
  }

  @Override
  public int order() {
    return 0;
  }

  @Override
  public boolean apply(String action, ActionRequest actionRequest, ActionListener<?> listener) {

    // Skip if disabled
    if (!conf.enabled) {
      return true;
    }

    RestRequest req = ThreadRepo.request.get();
    RestChannel channel = ThreadRepo.channel.get();

    boolean reqNull = req == null;
    boolean chanNull = channel == null;

    // This was not a REST message
    if (reqNull && chanNull) {
      return true;
    }

    // Bailing out in case of catastrophical misconfiguration that would lead to insecurity
    if (reqNull != chanNull) {
      if (chanNull)
        throw new SecurityPermissionException("Problems analyzing the channel object. Have you checked the security permissions?", null);
      if (reqNull)
        throw new SecurityPermissionException("Problems analyzing the request object. Have you checked the security permissions?", null);
    }

    RequestContext rc = new RequestContext(channel, req, action, actionRequest, indicesService, threadPool);
    BlockExitResult exitResult = acl.check(rc);

    // The request is allowed to go through
    if (exitResult.isMatch() && exitResult.getBlock().getPolicy() == Block.Policy.ALLOW) {
      return true;
    }

    // Barring
    logger.info("forbidden request: " + rc + " Reason: " + exitResult.getBlock() + " (" + exitResult.getBlock() + ")");
    String reason = conf.forbiddenResponse;

    BytesRestResponse resp;

    if (acl.isBasicAuthConfigured()) {
      resp = new BytesRestResponse(RestStatus.UNAUTHORIZED, reason);
      logger.debug("Sending login prompt header...");
      resp.addHeader("WWW-Authenticate", "Basic");
    } else {
      resp = new BytesRestResponse(RestStatus.FORBIDDEN, reason);
    }

    channel.sendResponse(resp);

    return false;
  }

  @Override
  public boolean apply(String s, ActionResponse actionResponse, ActionListener<?> actionListener) {
    return true;
  }
}

package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.ACL;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;

/**
 * Created by sscarduzio on 19/12/2015.
 */
public class IndexLevelActionFilter extends ActionFilter.Simple {
  private ACL acl = null;
  private ConfigurationHelper conf = null;

  @Inject
  public IndexLevelActionFilter(Settings settings) {
    super(settings);
    if (conf == null) {
      logger.info("Readonly REST plugin was loaded...");
      this.conf = new ConfigurationHelper(settings, logger);

      if (!conf.enabled) {
        logger.info("Readonly REST plugin is disabled!");
        return;
      }

      logger.info("Readonly REST plugin is enabled. Yay, ponies!");

      try {
        acl = new ACL(settings);
      } catch (RuleConfigurationError e) {
        logger.error("impossible to initialize ACL configuration", e);
        throw e;
      }
    }
  }

  @Override
  public int order() {
    return 0;
  }

  @Override
  public boolean apply(String action, ActionRequest actionRequest, final ActionListener listener) {

    // Skip if disabled
    if (!conf.enabled) {
      return true;
    }

    RestRequest req = actionRequest.getFromContext("request");
    RestChannel channel = actionRequest.getFromContext("channel");

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

    RequestContext rc = new RequestContext(channel, req, action, actionRequest);
    BlockExitResult exitResult = acl.check(rc);

    // The request is allowed to go through
    if (exitResult.isMatch() && exitResult.getBlock().getPolicy() == Block.Policy.ALLOW) {
      return true;
    }

    // Barring
    logger.debug("forbidden request: " + req + " Reason: " + exitResult.getBlock() + " (" + exitResult.getBlock() + ")");
    String reason = "Forbidden";
    if (conf.forbiddenResponse != null) {
      reason = conf.forbiddenResponse;
    }

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
  public boolean apply(String s, ActionResponse actionResponse, ActionListener actionListener) {
    return true;
  }
}

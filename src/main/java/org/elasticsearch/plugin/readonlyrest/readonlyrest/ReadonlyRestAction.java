package org.elasticsearch.plugin.readonlyrest.readonlyrest;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.rest.*;
import org.elasticsearch.plugin.readonlyrest.readonlyrest.acl.ACL;
import org.elasticsearch.plugin.readonlyrest.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.readonlyrest.acl.blocks.BlockExitResult;

/**
 * Readonly REST plugin. Adding some access control to the fast Netty based REST interface of Elasticsearch.
 * <p/>
 * This plugin is configurable from $ES_HOME/conf/elasticsearch.yml. Example configuration:
 * <p/>
 * <pre>
 * readonlyrest:
 *  enable: true
 *  auth_key: secretAuthKey // this can bypasses all other rules and allows for operation if matched
 *  allow_localhost: true
 *  whitelist: [192.168.1.144]
 *  forbidden_uri_re: .*bar_me_pls.*
 *  barred_reason_string: <h1>unauthorized</h1>
 * </pre>
 *
 * @author <a href="mailto:scarduzio@gmail.com">Simone Scarduzio</a>
 * @see <a href="https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/">Github Project</a>
 */

public class ReadonlyRestAction extends BaseRestHandler {

  private ACL acl;

  @Inject
  public ReadonlyRestAction(final Settings settings, Client client, RestController controller) {
    super(settings, controller, client);

    logger.info("Readonly REST plugin was loaded...");
    final ConfigurationHelper conf = new ConfigurationHelper(settings, logger);
    if (!conf.enabled) {
      logger.info("Readonly REST plugin is disabled!");
      return;
    }

    logger.info("Readonly REST plugin is enabled. Yay, ponies!");

    try {
      acl = new ACL(settings);
      logger.info("ACL configuration: OK");
    } catch (RuleConfigurationError e) {
      logger.error("impossible to initialize ACL configuration", e);
      throw e;
    }

    controller.registerFilter(new RestFilter() {

      @Override
      public void process(RestRequest request, RestChannel channel, RestFilterChain filterChain) {
        BlockExitResult exitResult = acl.check(request, channel);
        if (exitResult.isMatch() && exitResult.getBlock().getPolicy() == Block.Policy.ALLOW) {
          ok(request, filterChain, channel);
        } else {
          logger.trace("forbidden request: " + request + " Reason: " + exitResult.getBlock() + " (" + exitResult.getBlock() + ")");
          String reason = "Forbidden";
          if (conf.forbiddenResponse != null) {
            reason = conf.forbiddenResponse;
          }
          ko(channel, reason, acl.isBasicAuthConfigured());
        }
      }
    });
  }

  public void ok(RestRequest request, RestFilterChain filterChain, RestChannel channel) {
    filterChain.continueProcessing(request, channel);
  }

  public void ko(RestChannel channel, String reason, boolean shouldSendAuthPrompt) {

    RestResponse resp;
    if (shouldSendAuthPrompt) {
      resp = new BytesRestResponse(RestStatus.UNAUTHORIZED, reason);
      logger.debug("Sending login prompt header...");
      resp.addHeader("WWW-Authenticate", "Basic");
    }
    else {
      resp = new BytesRestResponse(RestStatus.FORBIDDEN, reason);
    }

    channel.sendResponse(resp);
  }


  @Override
  protected void handleRequest(RestRequest restRequest, RestChannel restChannel, Client client) throws Exception {
    // We do everything in the constructor
  }
}

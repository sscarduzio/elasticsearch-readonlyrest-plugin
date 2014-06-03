package org.elasticsearch.rest.action.readonlyrest;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestFilter;
import org.elasticsearch.rest.RestFilterChain;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.readonlyrest.acl.ACL;
import org.elasticsearch.rest.action.readonlyrest.acl.ACLRequest;

/**
 * Readonly REST plugin. Adding some access control to the fast Netty based REST interface of Elasticsearch.
 * 
 * This plugin is configurable from $ES_HOME/conf/elasticsearch.yml. Example configuration:
 * 
 * <pre>
 * readonlyrest: 
 *  enable: true 
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

    private ACL                        acl;

  @Inject
  public ReadonlyRestAction(final Settings settings, Client client, RestController controller) {
    super(settings, client);
    final ConfigurationHelper conf = new ConfigurationHelper(settings, logger);
    if(!conf.enabled){
      logger.info("ReadonlyRest plugin is disabled!");
      return;
    }
    try {
      acl = new ACL(logger, settings);
      logger.info("ACL configuration: OK");
    }
    catch (Exception e) {
      logger.error("impossible to initialize ACL configuration", e);
    }
    controller.registerFilter(new RestFilter() {

      @Override
      public void process(RestRequest request, RestChannel channel, RestFilterChain filterChain) {
        ACLRequest aclReq = new ACLRequest(request, channel);
        String reason = acl.check(aclReq);
        if(reason == null){
          ok(request, filterChain, channel);
        }
        else {
          if(conf.forbiddenResponse != null){
            reason = conf.forbiddenResponse;
          }
          ko(channel, reason);
        }
        
      }
    });
  }
  public void ok(RestRequest request, RestFilterChain filterChain, RestChannel channel ){
    filterChain.continueProcessing(request, channel);
  }
  public void ko(RestChannel channel, String reason){
    channel.sendResponse(new BytesRestResponse(RestStatus.FORBIDDEN, reason));
  }
  public void handleRequest(final RestRequest request, final RestChannel channel) {
  }
}

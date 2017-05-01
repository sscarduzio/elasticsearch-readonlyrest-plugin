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

package org.elasticsearch.plugin.readonlyrest.wiring;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestFilter;
import org.elasticsearch.rest.RestFilterChain;
import org.elasticsearch.rest.RestRequest;

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

  @Inject
  public ReadonlyRestAction(final Settings settings, Client client, RestController controller) {
    super(settings, controller, client);

    controller.registerFilter(new RestFilter() {

      @Override
      public void process(RestRequest request, RestChannel channel, RestFilterChain filterChain) {
        request.putInContext("request", request);
        request.putInContext("channel", channel);
        filterChain.continueProcessing(request, channel);
      }
    });
  }

  @Override
  protected void handleRequest(RestRequest restRequest, RestChannel restChannel, Client client) throws Exception {
    // We do everything in the constructor
  }

}

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

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestFilter;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestFilterChain;

import java.io.IOException;

/**
 * Created by sscarduzio on 17/11/2016.
 */
public class ReadonlyRestRestAction extends BaseRestHandler {

  @Inject
  public ReadonlyRestRestAction(Settings settings, RestController controller) {
    super(settings);

    controller.registerFilter(new RestFilter() {
      @Override
      public void process(RestRequest request, RestChannel channel, NodeClient client, RestFilterChain filterChain) throws Exception {
        System.out.println("rest: " + Thread.currentThread().getName() + "  - method: " + request.method());
        ThreadRepo.request.set(request);
        ThreadRepo.channel.set(channel);

        filterChain.continueProcessing(request, channel, client);
      }
    });
  }

  @Override
  protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
    return null;
  }
}
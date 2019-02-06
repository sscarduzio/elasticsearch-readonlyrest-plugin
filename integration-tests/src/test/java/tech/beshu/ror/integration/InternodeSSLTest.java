/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package tech.beshu.ror.integration;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.xpack.client.PreBuiltXPackTransportClient;
import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProject;

import java.net.InetAddress;
import java.util.Optional;

import static junit.framework.TestCase.assertTrue;

public class InternodeSSLTest {

  @ClassRule
  public static ESWithReadonlyRestContainer container =
      ESWithReadonlyRestContainer.create(
          RorPluginGradleProject.fromSystemProperty(),
          "/ssl_internode/elasticsearch.yml",
          Optional.empty()
      );

  @Test
  public void testSSLnoVerification() throws Exception {
    if(Integer.parseInt(container.getEsVersion().replace(".","")) < 660 ) {
      return;
    }
    TransportClient client = new PreBuiltXPackTransportClient(
        Settings.builder()
                .put("client.transport.sniff", true)
                .put("xpack.security.transport.ssl.verification_mode", "none")
                .put("xpack.security.transport.ssl.enabled", "true")
                .build())
        .addTransportAddress(new TransportAddress(
            InetAddress.getByName(
                container.getESHost()
                //  "localhost"
            ),
            //9300
            container.getTransportPort()
        ));
    ClusterHealthResponse resp = client.admin().cluster().prepareHealth().get();
    System.out.println(resp.getStatus());
    assertTrue(resp.getStatus().equals(ClusterHealthStatus.YELLOW));
    //  client.close();

  }

}

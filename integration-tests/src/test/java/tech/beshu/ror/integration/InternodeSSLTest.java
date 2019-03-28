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

/*
package tech.beshu.ror.integration;

import com.google.common.net.HostAndPort;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.client.transport.NoNodeAvailableException;
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

//  @ClassRule
//  public static ESWithReadonlyRestContainer multiContainerDependent =
//      ESWithReadonlyRestContainer.create(
//          RorPluginGradleProject.fromSystemProperty(),
//          "/ssl_internode/elasticsearch.yml",
//          Optional.empty()
//      );

  private HostAndPort getHostAndPort() {
    return HostAndPort.fromParts("localhost", 9300);
    //return HostAndPort.fromParts(multiContainerDependent.getESHost(), multiContainerDependent.getTransportPort());
  }

@Test
  public void testSSLnoVerification() throws Exception {
//    if (Integer.parseInt(multiContainerDependent.getEsVersion().replace(".", "")) < 660) {
//      return;
//    }
//    System.out.println("transport port:   " + multiContainerDependent.getTransportPort());

    TransportClient client = new PreBuiltXPackTransportClient(
        Settings.builder()
                .put("client.transport.sniff", true)
                .put("xpack.security.transport.ssl.enabled", "true")
                .put("xpack.ssl.verification_mode", "none")
                .build())
        .addTransportAddress(new TransportAddress(
            InetAddress.getByName(
                getHostAndPort().getHost()
            ),
            getHostAndPort().getPort()
        ));
    ClusterHealthResponse resp = client.admin().cluster().prepareHealth().get();
    System.out.println(resp.getStatus());
    assertTrue(resp.getStatus().equals(ClusterHealthStatus.YELLOW));
    client.close();
  }

  @Test(expected = NoNodeAvailableException.class)
  public void testSSLwithServerVerification() throws Exception {
    //    if (Integer.parseInt(multiContainerDependent.getEsVersion().replace(".", "")) < 660) {
    //      return;
    //    }
    //    System.out.println("transport port:   " + multiContainerDependent.getTransportPort());

    TransportClient client = new PreBuiltXPackTransportClient(
        Settings.builder()
                .put("client.transport.sniff", true)
                .put("xpack.security.transport.ssl.enabled", "true")
               // .put("xpack.ssl.verification_mode", "none")

                .build())
        .addTransportAddress(new TransportAddress(
            InetAddress.getByName(
                getHostAndPort().getHost()
            ),
            getHostAndPort().getPort()
        ));
    ClusterHealthResponse resp = client.admin().cluster().prepareHealth().get();
    System.out.println(resp.getStatus());
    assertTrue(resp.getStatus().equals(ClusterHealthStatus.YELLOW));
    client.close();
  }

}
*/
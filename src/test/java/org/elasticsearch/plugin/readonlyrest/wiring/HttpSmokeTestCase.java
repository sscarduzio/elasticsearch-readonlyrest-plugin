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

/**
 * Created by sscarduzio on 30/11/2016.
 */
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.transport.MockTcpTransportPlugin;
import org.elasticsearch.transport.Netty4Plugin;
import org.junit.BeforeClass;

import java.util.Arrays;
import java.util.Collection;

public abstract class HttpSmokeTestCase extends ESIntegTestCase {

  private static String nodeTransportTypeKey;
  private static String nodeHttpTypeKey;
  private static String clientTypeKey;

  @SuppressWarnings("unchecked")
  @BeforeClass
  public static void setUpTransport() {
    nodeTransportTypeKey = getTypeKey(randomFrom(MockTcpTransportPlugin.class, Netty4Plugin.class));
    nodeHttpTypeKey = getTypeKey(Netty4Plugin.class);
    clientTypeKey = getTypeKey(randomFrom(MockTcpTransportPlugin.class,Netty4Plugin.class));
  }

  private static String getTypeKey(Class<? extends Plugin> clazz) {
    if (clazz.equals(MockTcpTransportPlugin.class)) {
      return MockTcpTransportPlugin.MOCK_TCP_TRANSPORT_NAME;
    } else {
      assert clazz.equals(Netty4Plugin.class);
      return Netty4Plugin.NETTY_TRANSPORT_NAME;
    }
  }

  @Override
  protected Settings nodeSettings(int nodeOrdinal) {
    return Settings.builder()
        .put(super.nodeSettings(nodeOrdinal))
        .put(NetworkModule.TRANSPORT_TYPE_KEY, nodeTransportTypeKey)
        .put(NetworkModule.HTTP_TYPE_KEY, nodeHttpTypeKey)
        .put(NetworkModule.HTTP_ENABLED.getKey(), true).build();
  }

  @Override
  protected Collection<Class<? extends Plugin>> nodePlugins() {
    return Arrays.asList(MockTcpTransportPlugin.class, Netty4Plugin.class);
  }

  @Override
  protected Collection<Class<? extends Plugin>> transportClientPlugins() {
    return Arrays.asList(MockTcpTransportPlugin.class, Netty4Plugin.class);
  }

  @Override
  protected Settings transportClientSettings() {
    return Settings.builder()
        .put(super.transportClientSettings())
        .put(NetworkModule.TRANSPORT_TYPE_KEY, clientTypeKey)
        .build();
  }

  @Override
  protected boolean ignoreExternalCluster() {
    return true;
  }

}
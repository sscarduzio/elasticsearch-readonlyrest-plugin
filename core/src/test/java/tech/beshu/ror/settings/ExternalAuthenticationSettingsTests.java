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
package tech.beshu.ror.settings;

import org.junit.Assert;
import org.junit.Test;
import tech.beshu.ror.TestUtils;
import tech.beshu.ror.settings.definitions.ExternalAuthenticationServiceSettingsCollection;

import java.time.Duration;

public class ExternalAuthenticationSettingsTests {

  @Test
  public void testSuccessfulCreationFromRequiredSettingsWithoutHttpConnection() {
    ExternalAuthenticationServiceSettingsCollection settings = ExternalAuthenticationServiceSettingsCollection.from(
            TestUtils.fromYAMLString("" +
                    "external_authentication_service_configs:\n" +
                    "    - name: \"ext1\"\n" +
                    "      authentication_endpoint: \"http://localhost:8080/auth1\"\n" +
                    "      success_status_code: 200\n" +
                    "      cache_ttl_in_sec: 60\n" +
                    "      validate: true\n" +
                    "\n" +
                    "    - name: \"ext2\"\n" +
                    "      authentication_endpoint: \"http://192.168.0.1:8080/auth2\"\n" +
                    "      success_status_code: 204\n" +
                    "      cache_ttl_in_sec: 60\n" +
                    "      validate: false"
            )
    );

    Assert.assertEquals("Uses default connection pool size", 30, (long) settings.get("ext1").getHttpConnectionSettings().getConnectionPoolSize());
    Assert.assertEquals("http://localhost:8080/auth1", settings.get("ext1").getEndpoint().toString());
    Assert.assertEquals("http://192.168.0.1:8080/auth2", settings.get("ext2").getEndpoint().toString());
  }

  @Test
  public void testSuccessfulCreationFromRequiredSettingsWithHttpConnection() {
    ExternalAuthenticationServiceSettingsCollection settings = ExternalAuthenticationServiceSettingsCollection.from(
            TestUtils.fromYAMLString("" +
                    "external_authentication_service_configs:\n" +
                    "    - name: \"ext1\"\n" +
                    "      authentication_endpoint: \"http://localhost:8080/auth1\"\n" +
                    "      success_status_code: 200\n" +
                    "      cache_ttl_in_sec: 60\n" +
                    "      validate: true\n" +
                    "      http_connection_settings:\n" +
                    "          connection_timeout_in_sec: 30\n" +
                    "          socket_timeout_in_sec: 8\n" +
                    "          connection_request_timeout_in_sec: 12\n" +
                    "          connection_pool_size: 100\n"
            )
    );

    HttpConnectionSettings connectionSettings = settings.get("ext1").getHttpConnectionSettings();
    Assert.assertEquals(Duration.ofSeconds(30), connectionSettings.getConnectTimeout());
    Assert.assertEquals(Duration.ofSeconds(8), connectionSettings.getSocketTimeout());
    Assert.assertEquals(Duration.ofSeconds(12), connectionSettings.getConnectionRequestTimeout());
    Assert.assertEquals(100, (long) connectionSettings.getConnectionPoolSize());
  }
}

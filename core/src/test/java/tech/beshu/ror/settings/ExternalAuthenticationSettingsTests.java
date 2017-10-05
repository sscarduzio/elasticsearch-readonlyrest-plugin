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

import tech.beshu.ror.TestUtils;
import tech.beshu.ror.settings.definitions.ExternalAuthenticationServiceSettingsCollection;
import org.junit.Test;

import java.net.URISyntaxException;

public class ExternalAuthenticationSettingsTests {

  @Test
  public void testSuccessfulCreationFromRequiredSettings() throws URISyntaxException {
    ExternalAuthenticationServiceSettingsCollection.from(
      TestUtils.fromYAMLString("" +
                                 "external_authentication_service_configs:\n" +
                                 "    - name: \"ext1\"\n" +
                                 "      authentication_endpoint: \"http://localhost:8080/auth1\"\n" +
                                 "      success_status_code: 200\n" +
                                 "      cache_ttl_in_sec: 60\n" +
                                 "\n" +
                                 "    - name: \"ext2\"\n" +
                                 "      authentication_endpoint: \"http://192.168.0.1:8080/auth2\"\n" +
                                 "      success_status_code: 204\n" +
                                 "      cache_ttl_in_sec: 60"
      )
    );
  }
}

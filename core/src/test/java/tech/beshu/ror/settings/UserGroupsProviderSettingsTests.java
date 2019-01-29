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

import org.junit.Test;
import tech.beshu.ror.TestUtils;
import tech.beshu.ror.settings.definitions.__old_UserGroupsProviderSettingsCollection;

import java.net.URISyntaxException;

public class UserGroupsProviderSettingsTests {

  @Test
  public void testSuccessfulCreationFromRequiredSettings() throws URISyntaxException {
    __old_UserGroupsProviderSettingsCollection.from(
      TestUtils.fromYAMLString("" +
                                 "user_groups_providers:\n" +
                                 "\n" +
                                 "  - name: GroupsService1\n" +
                                 "    groups_endpoint: \"http://localhost:8080/groups\"\n" +
                                 "    auth_token_name: \"user\"\n" +
                                 "    auth_token_passed_as: QUERY_PARAM\n" +
                                 "    response_groups_json_path: \"$..groups[?(@.name)].name\"\n" +
                                 "\n" +
                                 "  - name: GroupsService2\n" +
                                 "    groups_endpoint: \"http://192.168.0.1:8080/groups\"\n" +
                                 "    auth_token_name: \"auth_token\"\n" +
                                 "    auth_token_passed_as: HEADER\n" +
                                 "    response_groups_json_path: \"$..groups[?(@.name)].name\""
      )
    );
  }

}

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

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutionException;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoTimeout;

/**
 * Created by sscarduzio on 27/11/2016.
 */
@ESIntegTestCase.ClusterScope(supportsDedicatedMasters = false, numDataNodes = 1, numClientNodes = 0)
public class ConfigurationTests extends ESIntegTestCase {
  @Override
  protected Collection<Class<? extends Plugin>> nodePlugins() {
    return Arrays.asList(ReadonlyRestPlugin.class);
  }

  @Override
  protected Settings nodeSettings(int nodeOrdinal) {
    System.out.println("testing the configuration");
    String text = ("readonlyrest:\n" +
        "    # (De)activate plugin\n" +
        "    enable: true\n" +
        "\n" +
        "    ssl:\n" +
        "      enable: true\n" +
        "      keystore_file: \"/elasticsearch/plugins/readonlyrest/keystore.jks\"\n" +
        "      keystore_pass: readonlyrest\n" +
        "      key_pass: readonlyrest\n" +
        "\n" +
        "    # HTTP response body in case of forbidden request.\n" +
        "    # If this is null or omitted, the name of the first violated access control rule is returned (useful for debugging!)\n" +
        "    response_if_req_forbidden: <h1>Forbidden</h1>\n" +
        "\n" +
        "    # Default policy is to forbid everything, so let's define a whitelist\n" +
        "    access_control_rules:\n" +
        "\n" +
        "    - name: 1\n" +
        "      type: allow\n" +
        "      accept_x-forwarded-for_header: true\n" +
        "      hosts: [9.9.9.9]\n" +
        "\n" +
        "    - name: 2\n" +
        "      type: allow\n" +
        "      auth_key: sales:p455wd\n" +
        "\n" +
        "    - name: 3\n" +
        "      type: allow\n" +
        "      api_keys: [1234567890]\n" +
        "\n" +
        "    - name: 4\n" +
        "      type: allow\n" +
        "      hosts: [127.0.0.1, 192.168.1.0/24]\n" +
        "\n" +
        "    - name: 5\n" +
        "      type: forbid\n" +
        "      uri_re: ^/secret-idx/.*\n" +
        "#\n" +
        "#    - name: 6\n" +
        "#      type: allow\n" +
        "#      indices: public-idx\n" +
        "#\n" +
        "#    - name: 7\n" +
        "#      type: allow\n" +
        "#      hosts: [192.168.64.1]\n" +
        "#      indices: ip-based-idx\n" +
        "#\n" +
        "    - name: 8\n" +
        "      type: allow\n" +
        //   "      maxBodyLength: 0\n" +
        "      methods: [OPTIONS,GET]\n" +
        "\n" +
        "#    - name: 9\n" +
        "#      type: allow\n" +
        "#      indices: wildcard-*\n" +
        "\n" +
        "#    - name: 10\n" +
        "#      type: allow\n" +
        "#      indices: withplus\n" +
        "\n" +
        "    - name: 11\n" +
        "      type: allow\n" +
        "      actions: action1\n" +
        "\n" +
        "    - name: 12\n" +
        "      type: allow\n" +
        "      actions: action*\n" +
        "\n" +
        "#    - name: 13\n" +
        "#      type: allow\n" +
        "#      indices: [\"<no-index>\"]\n" +
        "\n" +
        "#    - name: 14\n" +
        "#      type: allow\n" +
        "#      hosts: [1.1.1.2]\n" +
        "#      indices: [\"i1\", \"i2\", \"i3\"]\n" +
        "\n" +
        "    - name: 15\n" +
        "      type: allow\n" +
        "      auth_key_sha1: a5aa590854b3806350b345ea154a52e3391aed32 #sha1configured:p455wd\n" +
        "\n" +
        "    - name: 16\n" +
        "      type: allow\n" +
        "      groups: [\"b\"]\n" +
        "\n" +
        "\n" +
        "    users:\n" +
        "\n" +
        "    - username: alice\n" +
        "      auth_key: alice:p455phrase\n" +
        "      groups: [\"b\", \"c\"]\n" +
        "\n" +
        "    - username: bob\n" +
        "      auth_key: bob:s3cr37\n" +
        "      groups: [\"c\"]\n" +
        "\n" +
        "    - username: claire\n" +
        "      auth_key_sha1: 2bc37a406bd743e2b7a4cb33efc0c52bc2cb03f0\n" +
        "      groups: [\"b\"]");

    return Settings.builder().loadFromSource(text).build();
  }

  public void testJoin() throws ExecutionException, InterruptedException {
    // only wait for the cluster to form
    assertNoTimeout(client().admin().cluster().prepareHealth().setWaitForNodes(Integer.toString(1)).get());
    // add one more node and wait for it to join
    internalCluster().startDataOnlyNodeAsync().get();
    assertNoTimeout(client().admin().cluster().prepareHealth().setWaitForNodes(Integer.toString(2)).get());
  }
}

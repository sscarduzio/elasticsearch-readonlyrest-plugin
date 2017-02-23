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
package org.elasticsearch.plugin.readonlyrest.integration;

import com.google.common.collect.Maps;
import junit.framework.TestCase;
import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer.ESInitalizer;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainerUtils;
import org.elasticsearch.plugin.readonlyrest.utils.containers.LdapContainer;
import org.elasticsearch.plugin.readonlyrest.utils.containers.MultiContainer;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Example extends TestCase {

    @ClassRule
    public static ESWithReadonlyRestContainer container = ESWithReadonlyRestContainerUtils.create(
            new MultiContainer.Builder()
                    .add("LDAP1", () -> LdapContainer.create("/test_example.ldif"))
                    .add("LDAP2", () -> LdapContainer.create("/test_example.ldif"))
                    .build(),
            "/test_elasticsearch.yml",
            new ElasticsearchTweetsInitializer()
    );

    @Test
    public void test() throws IOException {
        Response response = esRestClient("cartman", "pass")
                .performRequest("GET", "twitter/tweet/1");
        Assert.assertEquals(200, response.getStatusLine().getStatusCode());
    }

    private RestClient esRestClient(String name, String password) {
        return container.getClient(name, password);
    }

    private static class ElasticsearchTweetsInitializer implements ESInitalizer {
        @Override
        public void initialize(RestClient client) {
            createTweet(client, "1", "cartman",
                    "You can't be the dwarf character, Butters, I'm the dwarf.");
            createTweet(client, "2", "morgan",
                    "Let me tell you something my friend. Hope is a dangerous thing. Hope can drive a man insane.");
            createTweet(client, "3", "bong",
                    "Alright! Check out this bad boy: 12 megabytes of RAM, 500 megabyte hard drive, built-in " +
                            "spreadhseet capabilities and a modem that transmits it over 28,000 bps. ");
        }

        private void createTweet(RestClient client, String id, String user, String message) {
            try {
                client.performRequest(
                        "PUT",
                        "twitter/tweet/" + id,
                        Maps.newHashMap(),
                        new StringEntity(
                                "{\n" +
                                        "\"user\" : \"" + user + "\",\n" +
                                        "\"post_date\" : \"" + LocalDateTime.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "\",\n" +
                                        "\"message\" : \"" + message + "\"\n" +
                                        "}"));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

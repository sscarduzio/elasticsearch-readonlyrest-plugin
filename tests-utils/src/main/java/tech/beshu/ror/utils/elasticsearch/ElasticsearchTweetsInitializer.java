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
package tech.beshu.ror.utils.elasticsearch;

import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class ElasticsearchTweetsInitializer implements ESWithReadonlyRestContainer.ESInitalizer {

  @Override
  public void initialize(RestClient client) {
    createTweet(client, "1", "cartman",
        "You can't be the dwarf character, Butters, I'm the dwarf."
    );
    createPost(client, "2", "morgan",
        "Let me tell you something my friend. Hope is a dangerous thing. Hope can drive a man insane."
    );
    createPost(client, "1", "elon", "We're going to Mars!");
    createTweet(client, "3", "bong",
        "Alright! Check out this bad boy: 12 megabytes of RAM, 500 megabyte hard drive, built-in " +
            "spreadhseet capabilities and a modem that transmits it over 28,000 bps. "
    );
  }

  private void createTweet(RestClient client, String id, String user, String message) {
    createMessage(client, "twitter/tweet/", id, user, message);
  }

  private void createPost(RestClient client, String id, String user, String message) {
    createMessage(client, "facebook/post/", id, user, message);
  }

  private void createMessage(RestClient client, String endpoint, String id, String user, String message) {
    try {
      HttpPut httpPut = new HttpPut(client.from(endpoint + id));
      httpPut.setHeader("Content-Type", "application/json");
      httpPut.setEntity(new StringEntity(
          "{\n" +
              "\"@timestamp\" :" + LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) + ",\n" +
              "\"user\" : \"" + user + "\",\n" +
              "\"post_date\" : \"" + LocalDateTime.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "\",\n" +
              "\"message\" : \"" + message + "\"\n" +
              "}"));
      EntityUtils.consume(client.execute(httpPut).getEntity());
    } catch (Exception e) {
      e.printStackTrace();
      throw new IllegalStateException("Creating message failed", e);
    }
  }
}

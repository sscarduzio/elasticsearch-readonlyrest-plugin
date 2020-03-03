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

import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.elasticsearch.DeleteByQueryManagerJ;
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer;
import tech.beshu.ror.utils.elasticsearch.JBaseManager.SimpleResponse;
import tech.beshu.ror.utils.gradle.RorPluginGradleProjectJ;

import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class DeleteByQueryTests {

  @ClassRule
  public static ESWithReadonlyRestContainer container = ESWithReadonlyRestContainer.create(
      RorPluginGradleProjectJ.fromSystemProperty(), "/delete_by_query/elasticsearch.yml",
      Optional.of(new ElasticsearchTweetsInitializer())
  );

  private DeleteByQueryManagerJ blueTeamDeleteByQueryManager = new DeleteByQueryManagerJ(container.getBasicAuthClient("blue", "dev"));
  private DeleteByQueryManagerJ redTeamDeleteByQueryManager = new DeleteByQueryManagerJ(container.getBasicAuthClient("red", "dev"));

  private String matchAllQuery = "{\"query\" : {\"match_all\" : {}}}\n";

  @Test
  public void blueTeamShouldBeAbleToDeleteByQuery() {
    SimpleResponse result = blueTeamDeleteByQueryManager.delete("twitter", matchAllQuery);
    assertEquals(200, result.getResponseCode());
  }

  @Test
  public void redTeamShouldNotBeAbleToDeleteByQuery() {
    SimpleResponse result = redTeamDeleteByQueryManager.delete("facebook", matchAllQuery);
    assertEquals(401, result.getResponseCode());
  }

}

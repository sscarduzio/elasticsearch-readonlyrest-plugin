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

import static org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer.create;
import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer;
import org.elasticsearch.plugin.readonlyrest.utils.httpclient.RestClient;
import org.junit.ClassRule;
import org.junit.Test;

import com.google.common.collect.Maps;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

public class JwtAuthTests {

  private static final String ALGO = "HS256";
  private static final String KEY = "123456";
  private static final String WRONG_KEY = "abcdef";
  private static final String SUBJECT = "test";
  private static final String USER_CLAIM = "user";
  private static final String EXP = "exp";

  @ClassRule
  public static ESWithReadonlyRestContainer container = create(
      "/jwt_auth/elasticsearch.yml",
      Optional.empty());

  @Test
  public void rejectRequestWithoutAuthorizationHeader() throws Exception {
    int sc = test(Optional.empty());
    assertEquals(401, sc);
  }

  @Test
  public void rejectTokenWithWrongKey() throws Exception {
    int sc = test(makeToken(WRONG_KEY));
    assertEquals(401, sc);
  }

  @Test
  public void rejectTokentWithoutUserClaim() throws Exception {
    int sc = test(makeToken(KEY));
    assertEquals(401, sc);
  }

  @Test
  public void acceptValidTokentWithUserClaim() throws Exception {
    int sc = test(makeToken(KEY, makeClaimMap(USER_CLAIM, "user")));
    assertEquals(200, sc);
  }

  @Test
  public void rejectExpiredToken() throws Exception {
    int sc = test(makeToken(KEY, makeClaimMap(USER_CLAIM, "user", EXP, 0)));
    assertEquals(401, sc);
  }

  private int test(Optional<String> token) throws Exception {
    RestClient rc = container.getClient();
    HttpGet req = new HttpGet(rc.from("/_cat/indices"));
    token.ifPresent(t -> req.addHeader("Authorization", "Bearer " + t));
    HttpResponse resp = rc.execute(req);
    return resp.getStatusLine().getStatusCode();
  }

  private Optional<String> makeToken(String key) {
    return makeToken(key, Maps.newHashMap());
  }

  private Optional<String> makeToken(String key, Map<String, Object> claims) {
     JwtBuilder builder = Jwts.builder()
        .setSubject(SUBJECT)
        .signWith(SignatureAlgorithm.valueOf(ALGO), key.getBytes());
     claims.forEach((k, v) -> builder.claim(k, v));
     return Optional.of(builder.compact());
  }

  private Map<String, Object> makeClaimMap(Object ...kvs) {
    assert kvs.length % 2 == 0;
    HashMap<String, Object> claims = Maps.newHashMap();
    for (int i = 0; i < kvs.length; i += 2)
      claims.put((String) kvs[i], kvs[i + 1]);
    return claims;
  }
}

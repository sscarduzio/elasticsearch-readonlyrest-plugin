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

package org.elasticsearch.plugin.readonlyrest.rules;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.mock.orig.Mockito;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.JwtAuthSyncRule;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

public class JwtAuthRuleTests {

  private static final String SETTINGS_SIGNATURE_KEY = "jwt_auth.signature.key";
  private static final String SETTINGS_USER_CLAIM = "jwt_auth.user_claim";
  private static final String ALGO = "HS256";
  private static final String SECRET = "123456";
  private static final String BAD_SECRET = "abcdef";
  private static final String SUBJECT = "test";
  private static final String USER_CLAIM = "user";
  private static final String USER1 = "user1";

  @Test
  public void shouldAcceptTokenWithValidSignature() {
    String token = Jwts.builder()
      .setSubject(SUBJECT)
      .signWith(SignatureAlgorithm.valueOf(ALGO), SECRET.getBytes())
      .compact();
    Settings settings = Settings.builder()
        .put(SETTINGS_SIGNATURE_KEY, SECRET)
        .build();
    RequestContext rc = getMock(token);

    Optional<SyncRule> rule = JwtAuthSyncRule.fromSettings(settings);
    Optional<RuleExitResult> res = rule.map(r -> r.match(rc));
    rc.commit();

    assertTrue(rule.isPresent());
    assertTrue(res.isPresent());
    assertTrue(res.get().isMatch());
  }

  @Test
  public void shouldRejectTokenWithInvalidSignature() {
    String token = Jwts.builder()
      .setSubject(SUBJECT)
      .signWith(SignatureAlgorithm.valueOf(ALGO), SECRET.getBytes())
      .compact();
    Settings settings = Settings.builder()
        .put(SETTINGS_SIGNATURE_KEY, BAD_SECRET)
        .build();
    RequestContext rc = getMock(token);

    Optional<SyncRule> rule = JwtAuthSyncRule.fromSettings(settings);
    Optional<RuleExitResult> res = rule.map(r -> r.match(rc));
    rc.commit();

    assertTrue(rule.isPresent());
    assertTrue(res.isPresent());
    assertFalse(res.get().isMatch());
  }

  @Test
  public void shouldAcceptAUserClaimSetting() {
    Settings settings = Settings.builder()
        .put(SETTINGS_SIGNATURE_KEY, SECRET)
        .put(SETTINGS_USER_CLAIM, USER_CLAIM)
        .build();
    Optional<SyncRule> rule = JwtAuthSyncRule.fromSettings(settings);
    assertTrue(rule.isPresent());
  }

  @Test
  public void shouldRejectTokensWithoutTheConfiguredUserClaim() {
    String token = Jwts.builder()
        .setSubject(SUBJECT)
        .signWith(SignatureAlgorithm.valueOf(ALGO), SECRET.getBytes())
        .compact();
    Settings settings = Settings.builder()
        .put(SETTINGS_SIGNATURE_KEY, SECRET)
        .put(SETTINGS_USER_CLAIM, USER_CLAIM)
        .build();
    RequestContext rc = getMock(token);

    Optional<SyncRule> rule = JwtAuthSyncRule.fromSettings(settings);
    Optional<RuleExitResult> res = rule.map(r -> r.match(rc));
    rc.commit();

    assertTrue(rule.isPresent());
    assertTrue(res.isPresent());
    assertFalse(res.get().isMatch());
  }

  @Test
  public void shouldSetUserPresentInTokenWhenUserClaimIsConfigured() {
    String token = Jwts.builder()
        .setSubject(SUBJECT)
        .claim(USER_CLAIM, USER1)
        .signWith(SignatureAlgorithm.valueOf(ALGO), SECRET.getBytes())
        .compact();
    Settings settings = Settings.builder()
        .put(SETTINGS_SIGNATURE_KEY, SECRET)
        .put(SETTINGS_USER_CLAIM, USER_CLAIM)
        .build();
    RequestContext rc = getMock(token);

    Optional<SyncRule> rule = JwtAuthSyncRule.fromSettings(settings);
    Optional<RuleExitResult> res = rule.map(r -> r.match(rc));
    rc.commit();

    assertTrue(rule.isPresent());
    assertTrue(res.isPresent());
    assertTrue(res.get().isMatch());
    verify(rc).setLoggedInUser(new LoggedUser(USER1));
  }

  @Test
  public void shouldNotSetUserWhenUserClaimIsNotConfigured() {
    String token = Jwts.builder()
        .setSubject(SUBJECT)
        .claim(USER_CLAIM, USER1)
        .signWith(SignatureAlgorithm.valueOf(ALGO), SECRET.getBytes())
        .compact();
    Settings settings = Settings.builder()
        .put(SETTINGS_SIGNATURE_KEY, SECRET)
        .build();
    RequestContext rc = getMock(token);

    Optional<SyncRule> rule = JwtAuthSyncRule.fromSettings(settings);
    Optional<RuleExitResult> res = rule.map(r -> r.match(rc));
    rc.commit();

    assertTrue(rule.isPresent());
    assertTrue(res.isPresent());
    assertTrue(res.get().isMatch());
    verify(rc, never()).setLoggedInUser(any());
  }

  private RequestContext getMock(String token) {
    RequestContext mock = Mockito.mock(RequestContext.class);
    when(mock.getHeaders()).thenReturn(ImmutableMap.of("Authorization", "Bearer " + token));
    return mock;
  }
}

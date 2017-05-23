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
package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map.Entry;
import java.util.Optional;

import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.mocks.MockedESContext;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.rules.JwtAuthRuleSettings;
import org.junit.Test;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.mockito.Mockito;

public class JwtAuthRuleTests {

  private static final String SETTINGS_SIGNATURE_KEY = JwtAuthRuleSettings.SIGNATURE_KEY;
  private static final String SETTINGS_USER_CLAIM = JwtAuthRuleSettings.USER_CLAIM;
  private static final String ALGO = "HS256";
  private static final String SECRET = "123456";
  private static final String BAD_SECRET = "abcdef";
  private static final String SUBJECT = "test";
  private static final String USER_CLAIM = "user";
  private static final String USER1 = "user1";
  private static final String EMPTY_VAR = "HOPE_THIS_VARIABLE_IS_NOT_IN_THE_ENVIRONMENT";

  @Test
  public void shouldAcceptTokenWithValidSignature() {
    String token = Jwts.builder()
      .setSubject(SUBJECT)
      .signWith(SignatureAlgorithm.valueOf(ALGO), SECRET.getBytes())
      .compact();
    RawSettings settings = makeSettings(SETTINGS_SIGNATURE_KEY, SECRET);
    RequestContext rc = getMock(token);

    Optional<SyncRule> rule = makeRule(settings);
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
    RawSettings settings = makeSettings(SETTINGS_SIGNATURE_KEY, BAD_SECRET);
    RequestContext rc = getMock(token);

    Optional<SyncRule> rule = makeRule(settings);
    Optional<RuleExitResult> res = rule.map(r -> r.match(rc));
    rc.commit();

    assertTrue(rule.isPresent());
    assertTrue(res.isPresent());
    assertFalse(res.get().isMatch());
  }

  @Test
  public void shouldAcceptAUserClaimSetting() {
    RawSettings settings = makeSettings(SETTINGS_SIGNATURE_KEY, SECRET,
                                        SETTINGS_USER_CLAIM, USER_CLAIM);
    Optional<SyncRule> rule = makeRule(settings);
    assertTrue(rule.isPresent());
  }

  @Test
  public void shouldRejectTokensWithoutTheConfiguredUserClaim() {
    String token = Jwts.builder()
        .setSubject(SUBJECT)
        .signWith(SignatureAlgorithm.valueOf(ALGO), SECRET.getBytes())
        .compact();
    RawSettings settings = makeSettings(SETTINGS_SIGNATURE_KEY, SECRET,
                                        SETTINGS_USER_CLAIM, USER_CLAIM);
    RequestContext rc = getMock(token);

    Optional<SyncRule> rule = makeRule(settings);
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
    RawSettings settings = makeSettings(SETTINGS_SIGNATURE_KEY, SECRET,
                                        SETTINGS_USER_CLAIM, USER_CLAIM);
    RequestContext rc = getMock(token);

    Optional<SyncRule> rule = makeRule(settings);
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
    RawSettings settings = makeSettings(SETTINGS_SIGNATURE_KEY, SECRET);
    RequestContext rc = getMock(token);

    Optional<SyncRule> rule = makeRule(settings);
    Optional<RuleExitResult> res = rule.map(r -> r.match(rc));
    rc.commit();

    assertTrue(rule.isPresent());
    assertTrue(res.isPresent());
    assertTrue(res.get().isMatch());
    verify(rc, never()).setLoggedInUser(any());
  }

  @Test
  public void shouldSupportTextPrefixInSignatureKey() {
    RawSettings raw = makeSettings(SETTINGS_SIGNATURE_KEY, "text:" + SECRET);
    JwtAuthRuleSettings settings = JwtAuthRuleSettings.from(raw);
    assertArrayEquals(SECRET.getBytes(), settings.getKey());
  }

  @Test
  public void shouldSupportReadingKeyFromEnvironmentUsingEnvPrefix() {
    /* FIXME : figure a better way to test for variable substitution */
    Entry<String, String> vars = System.getenv()
        .entrySet()
        .stream()
        .filter(e -> !Strings.isNullOrEmpty(e.getValue()))
        .findAny()
        .get();
    String variable = vars.getKey();
    String value = vars.getValue();
    /* ************************************************************* */

    RawSettings raw = makeSettings(SETTINGS_SIGNATURE_KEY, "env:" + variable);
    JwtAuthRuleSettings settings = JwtAuthRuleSettings.from(raw);
    assertArrayEquals(value.getBytes(), settings.getKey());
  }

  @Test(expected=SettingsMalformedException.class)
  public void shouldFailWhenKeytIsEmpty() {
    RawSettings raw = makeSettings(SETTINGS_SIGNATURE_KEY, "");
    JwtAuthRuleSettings.from(raw);
  }

  @Test(expected=SettingsMalformedException.class)
  public void shouldFailWhenKeyFromEnvironmentIsEmpty() {
    RawSettings raw = makeSettings(SETTINGS_SIGNATURE_KEY, "env:" + EMPTY_VAR);
    JwtAuthRuleSettings.from(raw);
  }

  @Test(expected=SettingsMalformedException.class)
  public void shouldFailInAControlledFashionWhenKeyIsNotAString() {
    RawSettings raw = makeSettings(false, SETTINGS_SIGNATURE_KEY, "123456");
    JwtAuthRuleSettings.from(raw);
  }

  private RequestContext getMock(String token) {
    RequestContext mock = Mockito.mock(RequestContext.class);
    when(mock.getHeaders()).thenReturn(ImmutableMap.of("Authorization", "Bearer " + token));
    return mock;
  }

  private RawSettings makeSettings(String ...kvp) {
    return makeSettings(true, kvp);
  }

  private RawSettings makeSettings(boolean escapeValues, String ...kvp) {
    assert kvp.length % 2 == 0;

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < kvp.length; i += 2) {
      sb.append(kvp[i]);
      sb.append(": ");
      if (escapeValues) sb.append('"');
      sb.append(kvp[i + 1]);
      if (escapeValues) sb.append('"');
      sb.append("\n");
    }

    return RawSettings.fromString(sb.toString());
  }

  private Optional<SyncRule> makeRule(RawSettings settings) {
      try {
        return Optional.of(new JwtAuthSyncRule(JwtAuthRuleSettings.from(settings), MockedESContext.INSTANCE));
      } catch (Exception e) {
        e.printStackTrace();
        return Optional.empty();
      }
  }
}

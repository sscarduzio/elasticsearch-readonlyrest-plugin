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
package tech.beshu.ror.acl.blocks.rules.impl;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.Test;
import org.mockito.Mockito;
import tech.beshu.ror.TestUtils;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.acl.domain.LoggedUser;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.JwtAuthRuleSettings;

import java.security.KeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map.Entry;
import java.util.Optional;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JwtAuthRuleTests {

  private static final String SETTINGS_SIGNATURE_KEY = JwtAuthRuleSettings.SIGNATURE_KEY;
  private static final String SETTINGS_SIGNATURE_ALGO = JwtAuthRuleSettings.SIGNATURE_ALGO;
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
  public void shouldAcceptTokenWithValidRSASignature() throws KeyException {
    String token = Jwts.builder()
      .setSubject(SUBJECT)
      .signWith(SignatureAlgorithm.valueOf("RS256"), getRsaPrivateKey())
      .compact();

    RawSettings settings = makeSettings(SETTINGS_SIGNATURE_KEY, getRsaPublicKey(), SETTINGS_SIGNATURE_ALGO, "RSA");

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
  public void shouldRejectRSATokenWithInvalidSignature() throws KeyException {
    String token = Jwts.builder()
      .setSubject(SUBJECT)
      .signWith(SignatureAlgorithm.valueOf("RS256"), getRsaPrivateKey())
      .compact();

    RawSettings settings = makeSettings(SETTINGS_SIGNATURE_KEY, getInvalidPublicKey(), SETTINGS_SIGNATURE_ALGO, "RSA");

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
                                        SETTINGS_USER_CLAIM, USER_CLAIM
    );
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
                                        SETTINGS_USER_CLAIM, USER_CLAIM
    );
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
                                        SETTINGS_USER_CLAIM, USER_CLAIM
    );
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

  @Test(expected = SettingsMalformedException.class)
  public void shouldFailWhenKeytIsEmpty() {
    RawSettings raw = makeSettings(SETTINGS_SIGNATURE_KEY, "");
    JwtAuthRuleSettings.from(raw);
  }

  @Test(expected = SettingsMalformedException.class)
  public void shouldFailWhenKeyFromEnvironmentIsEmpty() {
    RawSettings raw = makeSettings(SETTINGS_SIGNATURE_KEY, "env:" + EMPTY_VAR);
    JwtAuthRuleSettings.from(raw);
  }

  @Test(expected = SettingsMalformedException.class)
  public void shouldFailInAControlledFashionWhenKeyIsNotAString() {
    RawSettings raw = makeSettings(false, SETTINGS_SIGNATURE_KEY, "123456");
    JwtAuthRuleSettings.from(raw);
  }

  private RequestContext getMock(String token) {
    RequestContext mock = Mockito.mock(RequestContext.class);
    when(mock.getHeaders()).thenReturn(ImmutableMap.of("Authorization", "Bearer " + token));
    return mock;
  }

  private RawSettings makeSettings(String... kvp) {
    return makeSettings(true, kvp);
  }

  private RawSettings makeSettings(boolean escapeValues, String... kvp) {
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

    return TestUtils.fromYAMLString(sb.toString());
  }

  private Optional<SyncRule> makeRule(RawSettings settings) {
    try {
      return Optional.of(new JwtAuthSyncRule(JwtAuthRuleSettings.from(settings), MockedESContext.INSTANCE));
    } catch (Exception e) {
      e.printStackTrace();
      return Optional.empty();
    }
  }

  private PrivateKey getRsaPrivateKey() throws KeyException {
    try {
      byte[] decoded = Base64.getMimeDecoder().decode("MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCzBElX1jA8I8K7\n" +
        "TXvdKV+nkvu+/qJOab50asTpDT/WlRVsL+wZLgi1+R6t5Qu4thWI3SmqEY3E0A9l\n" +
        "puM4vlICUiqrmPTm+UY41oQFMz4XwoP4cQh/E/g5nBykL3YPqkzYUoJhRknH+lna\n" +
        "wzEUafupH0N0Kc8eruG+9pM0BkLDweUFrHzXzY3C423LQSm5mYeglMYJlFcmJ9vo\n" +
        "MnCUmDPY4qTNlFy8U4ksBFBA1+q/ppFqeeOasAlHh7lnLAtR78I/rGLhVDBqAgO0\n" +
        "W2sOMDMLP584ll0zryYrulA7OEsQGYqQepSmUS9pm0243dl0gwsuGYbc0m5LP24B\n" +
        "F/RLQ2pJAgMBAAECggEAAPS+54cvTsLqIVHynWXBKwXv7j8x4rVR3RFM5+m4M48s\n" +
        "RB2lZyUFyuL/tPIKM/xU9RwpQs1BMpHh4ysW/5CUo4qIy83PUQR3yYnrvpNde4cA\n" +
        "aW1BHFyg8L3SsVXHjaHdMzKNm7NiZX0CydZNBsziGS8fjxlCD+njLr/mXVrDNIRs\n" +
        "SVQ+rZjnNIjflX7KnIYmLtN6a64mC/UPDobtmmadvyAf8Hc/o7JX1Iqy4wtIuEFb\n" +
        "qf82+xXPcEJqST0fFfWcMp3WEU0cyWNfFZWlmmqzMrJPqCJaRJMMFwawxHI4GQMW\n" +
        "W/3OyYT4ySdD/Lt/+rQRkR4BbI8J5h9CfNSrhYryeQKBgQDWlsXVQdgsVsC4pXay\n" +
        "LxjMf5zbcFxg+Jdp3koHpJS5my8cWTRFcRxyTFf8KDesKb/fEhYVV40CurZv4vKU\n" +
        "jHJYf+72QjAVWN6Wyjmxa9Ctc6n1OdZ4gHwBdYNnJJHXhihAbzT4kzF8uccFg6Oj\n" +
        "Es8csXdPnJ4huNN38FWhnfdpQwKBgQDVkCh6WkmjqYSh3F+Zr/sYCc+B42hvhIbt\n" +
        "OLr3U1PTqgv9DRtCfPcR1oJS0kilUo2Fd+4P3xV6EJTpOJbZdIYTRkxIrl6ORDkF\n" +
        "0Lp01Vnzv3DVjhpL4oMdWAVTC7BLJCN8inmz+Pf6RndJrBgLz2HQXMN3NCm5b+21\n" +
        "ojK0iGHvgwKBgFrdl0H5UrdbuNm3Pu6uoLqfYuVMy+FIAp2SwhhAabW6b5V6dHbf\n" +
        "MaN4jl05DnH5b8TenLlGzHAWbgAswnmCizzMV3yxhDjV29NQKGPneoKoEpTDe/yk\n" +
        "s13Oy+iWBKeVqF+4d162vWLKK+s61cTMxySoRRRSBmfTIsCL5Ua9ZDGPAoGAcn8X\n" +
        "NIGzeUspEJ5Vos/2jqyz069YDnG+5O/FTVQfXRuN0d10//B/hdC7jiuvRvM7bJMf\n" +
        "zuKLYSYCsAbm2S7fsvW9cDoL97ob2EJPtNOtpkC8/cFx171ZDiJiuGNL4P0/CUY0\n" +
        "eYjBaizdR2I8ghhtGIijQwV0WTbo+rg69w8ncoECgYBmf4xoW03WYtzGkinhN6FQ\n" +
        "SZt3/ATmJR0iLFzcvMncP+4xGq1J1oL7v0ArUX1mWGfJRS27zgH7k/qJprABnJnI\n" +
        "0TXjhBObmkicvOm11rYK2he2g+eW5RbZpr7FfrNuiZjMOmJn8dWHuwtboNcuEF3A\n" +
        "6Mzj9h2krlUiyKMi0IKLHw==");
      PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
      KeyFactory kf = KeyFactory.getInstance("RSA");
      return kf.generatePrivate(spec);
    } catch (Exception e) {
      throw new KeyException(e);
    }
  }

  private String getRsaPublicKey() {
    return "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAswRJV9YwPCPCu0173Slfp5L7vv6iTmm+dGrE6Q0/1pUVbC/sGS4ItfkereULuLYViN0pqhGNxNAPZabjOL5SAlIqq5j05vlGONaEBTM+F8KD+HEIfxP4OZwcpC92D6pM2FKCYUZJx/pZ2sMxFGn7qR9DdCnPHq7hvvaTNAZCw8HlBax8182NwuNty0EpuZmHoJTGCZRXJifb6DJwlJgz2OKkzZRcvFOJLARQQNfqv6aRannjmrAJR4e5ZywLUe/CP6xi4VQwagIDtFtrDjAzCz+fOJZdM68mK7pQOzhLEBmKkHqUplEvaZtNuN3ZdIMLLhmG3NJuSz9uARf0S0NqSQIDAQAB";
  }

  private String getInvalidPublicKey() {
    return getRsaPublicKey().replace("QAB", "QAC");
  }

}

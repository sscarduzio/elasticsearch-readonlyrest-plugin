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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ProxyAuthConfig;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ProxyAuthSyncRule;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class ProxyAuthRuleTests {

  private RuleExitResult match(String configured, String found) throws RuleNotConfiguredException {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(String configured, String found, RequestContext rc) throws RuleNotConfiguredException {
    when(rc.getHeaders()).thenReturn(ImmutableMap.of("X-Forwarded-User", found));

    ProxyAuthSyncRule r = ProxyAuthSyncRule.fromSettings(
        Settings.builder()
                .putArray("proxy_auth", configured)
                .build(),
        Lists.newArrayList()).get();

    RuleExitResult res = r.match(rc);
    rc.commit();
    return res;
  }

  @Test
  public void testOK() throws RuleNotConfiguredException {
    RuleExitResult res = match("1234567890", "1234567890");
    assertTrue(res.isMatch());
  }

  @Test
  public void testKO() throws RuleNotConfiguredException {
    RuleExitResult res = match("1234567890", "123");
    assertFalse(res.isMatch());
  }

  @Test
  public void testEmpty() throws RuleNotConfiguredException {
    RuleExitResult res = match("1234567890", "");
    assertFalse(res.isMatch());
  }

  @Test
  public void testBackCompatibilityOfProxyAuthLoadingFromSettings() {
    Settings settings = Settings.builder()
                                .putArray("proxy_auth", Lists.newArrayList("user1", "user2"))
                                .build();
    List<ProxyAuthConfig> configs = Lists.newArrayList(
        ProxyAuthConfig.fromSettings(
            Settings.builder()
                    .put("name", "proxy1")
                    .put("user_id_header", "X-Forwarded-User")
                    .build()
        )
    );
    assertTrue(ProxyAuthSyncRule.fromSettings(settings, configs).isPresent());
  }

  @Test
  public void testExtendedProxyAuthRuleLoadingFromSettings() {
    Settings settings = Settings.builder()
                                .put("proxy_auth.proxy_auth_config", "proxy1")
                                .putArray("proxy_auth.users", Lists.newArrayList("user1", "user2"))
                                .build();
    List<ProxyAuthConfig> configs = Lists.newArrayList(
        ProxyAuthConfig.fromSettings(
            Settings.builder()
                    .put("name", "proxy1")
                    .put("user_id_header", "X-Auth-Token")
                    .build()
        )
    );
    assertTrue(ProxyAuthSyncRule.fromSettings(settings, configs).isPresent());
  }

  @Test(expected = ConfigMalformedException.class)
  public void testCannotLoadExtendedProxyAuthRuleFromSettingsWhenProxyWasNotDefined() {
    Settings settings = Settings.builder()
                                .put("proxy_auth.proxy_auth_config", "proxy1")
                                .putArray("proxy_auth.users", Lists.newArrayList("user1", "user2"))
                                .build();
    List<ProxyAuthConfig> configs = Lists.newArrayList(
        ProxyAuthConfig.fromSettings(
            Settings.builder()
                    .put("name", "otherProxy")
                    .put("user_id_header", "X-Forwarded-User")
                    .build()
        )
    );
    assertFalse(ProxyAuthSyncRule.fromSettings(settings, configs).isPresent());
  }

}

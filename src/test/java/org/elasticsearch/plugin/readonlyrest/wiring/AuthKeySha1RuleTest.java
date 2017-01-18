package org.elasticsearch.plugin.readonlyrest.wiring;

import com.google.common.collect.ImmutableMap;
import junit.framework.TestCase;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha1Rule;
import org.mockito.Mockito;

import java.util.Base64;

import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class AuthKeySha1RuleTest extends TestCase {

  private RuleExitResult match(String configured, String found) throws RuleNotConfiguredException {
    return match(configured, found, null);
  }

  private RuleExitResult match(String configured, String found, RequestContext rc) throws RuleNotConfiguredException {
    if (rc == null) {
      rc = Mockito.mock(RequestContext.class);
    }
    when(rc.getHeaders()).thenReturn(ImmutableMap.of("Authorization", found));

    Rule r = new AuthKeySha1Rule(Settings.builder()
        .put("auth_key_sha1", configured)
        .build());

    RuleExitResult res = r.match(rc);
    return res;
  }

  public void testSimple() throws RuleNotConfiguredException {
    RuleExitResult res = match("4338fa3ea95532196849ae27615e14dda95c77b1",
        "Basic " + Base64.getEncoder().encodeToString("logstash:logstash".getBytes()));
    assertTrue(res.isMatch());
  }


}

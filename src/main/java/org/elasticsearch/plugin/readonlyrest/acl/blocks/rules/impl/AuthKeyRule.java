package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.base.Charsets;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class AuthKeyRule extends Rule {

  private String authKey;

  public AuthKeyRule(Settings s) throws RuleNotConfiguredException {
    super(s);

    String pAuthKey = s.get(this.KEY);
    if (pAuthKey != null && pAuthKey.trim().length() > 0) {
      authKey = Base64.encodeBytes(pAuthKey.getBytes(Charsets.UTF_8));
    }
    else {
      throw new RuleNotConfiguredException();
    }

  }

  // Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==
  private static String extractAuthFromHeader(String authorizationHeader) {
    if (authorizationHeader == null || authorizationHeader.trim().length() == 0 || !authorizationHeader.contains("Basic "))
      return null;
    String interestingPart = authorizationHeader.split("Basic")[1].trim();
    if (interestingPart.length() == 0) {
        return null;
    }
    return interestingPart;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    String authHeader = extractAuthFromHeader(rc.getRequest().header("Authorization"));

    if (authKey == null || authHeader == null) {
      return NO_MATCH;
    }

    String val = authHeader.trim();
    if (val.length() == 0) {
      return NO_MATCH;
    }

    return authKey.equals(authHeader) ? MATCH : NO_MATCH;
  }
}

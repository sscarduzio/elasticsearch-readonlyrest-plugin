package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

/**
 * Created by sscarduzio on 03/01/2017.
 */
public class SessionMaxIdleSyncRule extends SyncRule {
  private static final Logger logger = Loggers.getLogger(SessionMaxIdleSyncRule.class);
  private final long maxIdleMillis;

  public SessionMaxIdleSyncRule(Settings s) throws RuleNotConfiguredException {
    super(s);

    boolean isThisRuleConfigured = !Strings.isNullOrEmpty(s.get(getKey()));
    if (!isThisRuleConfigured) {
      throw new RuleNotConfiguredException();
    }

    boolean isLoginConfigured = !Strings.isNullOrEmpty(s.get(mkKey(AuthKeySyncRule.class)))
      || !Strings.isNullOrEmpty(s.get(mkKey(AuthKeySha1SyncRule.class)));

    if (isThisRuleConfigured && !isLoginConfigured) {
      logger.error(getKey() + " rule does not mean anything if you don't also set either "
                     + mkKey(AuthKeySha1SyncRule.class) + " or " + mkKey(AuthKeySyncRule.class));
      throw new RuleNotConfiguredException();
    }

    String tmp = s.get(getKey());

    Long timeMill = timeIntervalStringToMillis(tmp);

    if (timeMill <= 0) {
      throw new ElasticsearchParseException(getKey() + " value must be greater than zero");
    }
    maxIdleMillis = timeMill;
  }

  /*
  * Counts millis from stirngs like "1d 2h 3m", "15s"
  * */
  public static long timeIntervalStringToMillis(String input) {
    long result = 0;
    String number = "";
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (Character.isDigit(c)) {
        number += c;
      }
      else if (Character.isLetter(c) && !number.isEmpty()) {
        result += convert(Integer.parseInt(number), c);
        number = "";
      }
    }
    return result;
  }

  private static long convert(int value, char unit) {
    switch (unit) {
      case 'y':
        return value * 1000 * 60 * 60 * 24 * 365;
      case 'w':
        return value * 1000 * 60 * 60 * 24 * 7;
      case 'd':
        return value * 1000 * 60 * 60 * 24;
      case 'h':
        return value * 1000 * 60 * 60;
      case 'm':
        return value * 1000 * 60;
      case 's':
        return value * 1000;
      default:
        return 0;
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    SessionCookie c = new SessionCookie(rc, maxIdleMillis);

    // 1 no cookie
    if (!c.isCookiePresent()) {
      c.setCookie();
      return MATCH;
    }

    // 2 OkCookie
    if (c.isCookieValid()) {
      // Postpone the expiry date: we want to reset the login only for users that are INACTIVE FOR a period of time.
      c.setCookie();
      return MATCH;
    }

    // 2' BadCookie
    if (c.isCookiePresent() && !c.isCookieValid()) {
      c.unsetCookie();
      return NO_MATCH;
    }
    logger.error("Session handling panic! " + c + " RC:" + rc);
    return NO_MATCH;
  }
}

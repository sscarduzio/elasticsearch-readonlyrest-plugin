package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.hash.Hashing;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;

import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeyRule.getBasicAuthUser;

/**
 * Created by sscarduzio on 01/01/2017.
 */
public class SessionCookie {

  private static final ESLogger logger = Loggers.getLogger(SessionCookie.class);

  private static final String SERVER_SECRET = UUID.randomUUID().toString();

  private static final String COOKIE_NAME = "ReadonlyREST_Session";
  private static final String COOKIE_STRING_SEPARATOR = "__";
  private static final DateFormat df = new SimpleDateFormat("EEE MMM dd kk:mm:ss z yyyy", Locale.getDefault());
  private final Long sessionMaxIdleMillis;
  private final RequestContext rc;

  private boolean cookiePresent = false;
  private boolean cookieValid = false;

  public SessionCookie(RequestContext rc, Long sessionMaxIdleMillis) {
    if (sessionMaxIdleMillis <= 0) {
      throw new ElasticsearchException("session max idle interval cannot be negative");
    }
    this.sessionMaxIdleMillis = sessionMaxIdleMillis;
    this.rc = rc;

    // ----- Check cookie presence
    String cookieValue = extractCookie(COOKIE_NAME);

    if (Strings.isNullOrEmpty(cookieValue)) {
      this.cookiePresent = false;
      this.cookieValid = false;
      return;
    }

    this.cookiePresent = true;

    // ----- Check cookie validity
    String user = getBasicAuthUser(rc.getHeaders());
    Iterator<String> cookiePartsIterator =
        Splitter.on(COOKIE_STRING_SEPARATOR).trimResults().split(cookieValue).iterator();

    // Check user is the same with basic auth header
    String cookieUser = cookiePartsIterator.next();
    if (!cookieUser.equals(user)) {
      logger.info("this cookie does not belong to the user logged in as. Found in Cookie: "
          + cookieUser + " whilst in Authentication: " + user);
      return;
    }

    // Check the date is not expired
    Date now = new Date();
    Date cookieExpiryDate;
    try {
      cookieExpiryDate = df.parse(cookiePartsIterator.next());
      if (cookieExpiryDate.getTime() < new Date().getTime()) {
        logger.info("cookie was present but expired. Found: " + cookieExpiryDate + ", now it's " + now);
        return;
      }
    } catch (Exception e) {
      return;
    }

    // Check the signature matches
    String expectedCookieString = mkCookie(cookieUser, cookieExpiryDate);
    boolean signatureMatch = expectedCookieString.equals(cookieValue);
    if (!signatureMatch) {
      logger.info("cookie has wrong signature. Found " + cookieValue + " whilst expected: " + expectedCookieString);
      return;
    }
    this.cookieValid = true;
  }

  public Boolean isCookiePresent() {
    return cookiePresent;
  }

  public Boolean isCookieValid() {
    return cookieValid;
  }

  public void unsetCookie() {
    rc.setResponseHeader("Set-Cookie", COOKIE_NAME + "=");
  }

  public void setCookie() {
    String user = getBasicAuthUser(rc.getHeaders());
    Date expiryDateString = new Date(System.currentTimeMillis() + sessionMaxIdleMillis);
    String cookie = mkCookie(user, expiryDateString);
    rc.setResponseHeader("Set-Cookie", COOKIE_NAME + "=" + cookie);
  }

  private String mkCookie(String user, Date expiry) {
    return new StringBuilder()
        .append(user)
        .append(COOKIE_STRING_SEPARATOR)
        .append(expiry.toString())
        .append(COOKIE_STRING_SEPARATOR)
        .append(Hashing.sha1().hashString(SERVER_SECRET + user + expiry.getTime() / 1000, StandardCharsets.UTF_8))
        .toString();
  }

  private String extractCookie(String cookieName) {
    String cookieHeader = rc.getHeaders().get("Cookie");
    String cookieString = null;

    if (Strings.isNullOrEmpty(cookieHeader)) {
      return null;
    }

    cookieHeader = cookieHeader.trim();

    try {
      for (HttpCookie c : HttpCookie.parse(cookieHeader)) {
        if (c.getName().equals(cookieName)) {
          cookieString = c.getValue();
        }
      }

      if (Strings.isNullOrEmpty(cookieString)) {
        logger.info("no " + cookieName + "cookie found");
        return null;
      }

      cookieString = cookieString.trim();

    } catch (Exception e) {
      return null;
    }

    return cookieString;
  }

  @Override
  public String toString() {
    Joiner.MapJoiner mapJoiner = Joiner.on(",").withKeyValueSeparator("=");
    Map<String, Boolean> m = new HashMap<>(3);
    m.put("isCookiePresent", isCookiePresent());
    m.put("isCookieValid", isCookieValid());

    return "SessionCookie: " + mapJoiner.join(m);
  }

}
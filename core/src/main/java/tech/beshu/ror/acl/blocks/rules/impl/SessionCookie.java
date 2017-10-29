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

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.hash.Hashing;
import tech.beshu.ror.acl.domain.LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.RequestContext;

import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Created by sscarduzio on 01/01/2017.
 */
public class SessionCookie {

  private static final String SERVER_SECRET = UUID.randomUUID().toString();
  private static final String COOKIE_NAME = "ReadonlyREST_Session";
  private static final String COOKIE_STRING_SEPARATOR = "__";
  private static final DateFormat df = new SimpleDateFormat("EEE MMM dd kk:mm:ss z yyyy", Locale.getDefault());

  private final LoggerShim logger;
  private final Long sessionMaxIdleMillis;
  private final RequestContext rc;

  private boolean cookiePresent = false;
  private boolean cookieValid = false;

  SessionCookie(RequestContext rc, Long sessionMaxIdleMillis, ESContext context) {
    logger = context.logger(getClass());
    if (sessionMaxIdleMillis <= 0) {
      throw context.rorException("session max idle interval cannot be negative");
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
    String user = rc.getLoggedInUser().map(LoggedUser::getId).orElseGet(null);
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
    Optional<LoggedUser> user = rc.getLoggedInUser();
    if (!user.isPresent()) {
      logger.warn("Cannot state the logged in user, put the authentication rule on top of the block!");
      return;
    }
    Date expiryDateString = new Date(System.currentTimeMillis() + sessionMaxIdleMillis);
    String cookie = mkCookie(user.get().getId(), expiryDateString);
    rc.setResponseHeader("Set-Cookie", COOKIE_NAME + "=" + cookie);
  }

  private String mkCookie(String userName, Date expiry) {
    return new StringBuilder()
      .append(userName)
      .append(COOKIE_STRING_SEPARATOR)
      .append(expiry.toString())
      .append(COOKIE_STRING_SEPARATOR)
      .append(Hashing.sha1().hashString(SERVER_SECRET + userName + expiry.getTime() / 1000, StandardCharsets.UTF_8))
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
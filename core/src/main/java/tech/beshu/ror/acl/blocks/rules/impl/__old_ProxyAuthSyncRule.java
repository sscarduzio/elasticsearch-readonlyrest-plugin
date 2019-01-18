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
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authentication;
import tech.beshu.ror.acl.domain.__old_LoggedUser;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.__old_ProxyAuthRuleSettings;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Created by ah on 15/02/2016.
 */
public class __old_ProxyAuthSyncRule extends SyncRule implements Authentication {

  private final MatcherWithWildcards userListMatcher;
  private final __old_ProxyAuthRuleSettings settings;

  public __old_ProxyAuthSyncRule(__old_ProxyAuthRuleSettings s) {
    this.settings = s;
    this.userListMatcher = new MatcherWithWildcards(
      s.getUsers().stream()
        .filter(i -> !Strings.isNullOrEmpty(i))
        .distinct()
        .collect(Collectors.toSet())
    );
  }

  @Override
  public RuleExitResult match(__old_RequestContext rc) {
    Optional<__old_LoggedUser> optUser = getUser(rc.getHeaders());

    if (!optUser.isPresent()) {
      return NO_MATCH;
    }

    __old_LoggedUser user = optUser.get();
    RuleExitResult res = userListMatcher.match(user.getId()) ? MATCH : NO_MATCH;
    if (res.isMatch()) {
      rc.setLoggedInUser(user);
    }
    return res;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  private Optional<__old_LoggedUser> getUser(Map<String, String> headers) {
    String userId = headers.get(settings.getUserIdHeader());
    if (Strings.isNullOrEmpty(userId)) {
      return Optional.empty();
    }
    return Optional.of(new __old_LoggedUser(userId));
  }

}

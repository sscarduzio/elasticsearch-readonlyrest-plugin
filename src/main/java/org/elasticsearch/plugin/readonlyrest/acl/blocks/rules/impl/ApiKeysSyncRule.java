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

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.requestcontext.RequestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class ApiKeysSyncRule extends SyncRule {

  private final List<String> validApiKeys;

  private ApiKeysSyncRule(Settings s) throws RuleNotConfiguredException {
    ArrayList<String> keys = Lists.newArrayList(s.getAsArray(getKey()));
    if(keys.isEmpty()) throw new RuleNotConfiguredException();

    validApiKeys = keys.stream()
        .filter(key -> !Strings.isNullOrEmpty(key))
        .collect(Collectors.toList());
  }

  public static Optional<ApiKeysSyncRule> fromSettings(Settings s) {
    try {
      return Optional.of(new ApiKeysSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
      return Optional.empty();
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    String h = rc.getHeaders().get("X-Api-Key");
    if (validApiKeys == null || h == null) {
      return NO_MATCH;
    }
    if (validApiKeys.contains(h)) {
      return MATCH;
    }
    return NO_MATCH;
  }
}

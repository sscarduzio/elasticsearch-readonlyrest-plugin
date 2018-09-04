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
import com.google.common.collect.Sets;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.RuleSettings;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class HeadersSyncRule extends SyncRule {

  protected final  Set<String> allowedHeaders;
  protected final Settings settings;

  public HeadersSyncRule(Settings s) {
    this.allowedHeaders = s.getHeaderKeys();
    this.settings = s;
  }

  /**
   * We match headers in a way that the header name is case insensitive, and the header value is case sensitive
   *
   * @param rc the RequestContext
   * @return match or no match
   */
  @Override
  public RuleExitResult match(RequestContext rc) {

    Map<String, String> subsetHeaders = new HashMap<>(allowedHeaders.size());
    for (Map.Entry<String, String> kv : rc.getHeaders().entrySet()) {
      String lowerCaseKey = kv.getKey().toLowerCase();
      if (allowedHeaders.contains(lowerCaseKey)) {
        subsetHeaders.put(lowerCaseKey, kv.getValue());
      }
    }

    // First check that we have all the required headers
    if (subsetHeaders.size() < allowedHeaders.size()) {
      return NO_MATCH;
    }

    Set<String> flattenedActualHeaders = subsetHeaders
        .entrySet()
        .stream()
        .map(kv -> kv.getKey().toLowerCase() + ":" + kv.getValue())
        .collect(Collectors.toSet());

    return new MatcherWithWildcards(settings.getFlatHeaders())
        .filter(flattenedActualHeaders)
        .size() == allowedHeaders.size() ? MATCH : NO_MATCH;

  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  public static class Settings implements RuleSettings {
    public static final String ATTRIBUTE_NAME = "headers";
    private final Map<String, Set<String>> headers;
    private final Set<String> flatHeaders;

    public Settings(Set<String> headersWithValue) {
      headersWithValue.stream().filter(x -> !x.contains(":")).findFirst().ifPresent(x -> {
        throw new SettingsMalformedException("Headers should be in the form 'Key:Value' (separated by a colon)");
      });
      this.headers = new HashMap(headersWithValue.size());
      for (String kv : headersWithValue) {
        String[] kva = kv.toLowerCase().split(":", 2);
        if (shouldRejectDuplicateHeaderKey() && this.headers.keySet().contains(kva[0])) {
          throw new SettingsMalformedException(
              getName() + " rule: you can't require the same header (" + kva[0] + ") to have two values at the same time!");
        }
        if (Strings.isNullOrEmpty(kva[0])) {
          continue;
        }
        Set<String> valueSet = headers.get(kva[0]);
        if(valueSet == null){
          valueSet = Sets.newHashSet();
        }
        valueSet.add(kva[1]);
        this.headers.put(kva[0], valueSet);
      }

      // Only the header name should be lowercase (so it's compared case-insensitively)
      this.flatHeaders = this.headers
          .entrySet()
          .stream()
          .flatMap(kv -> kv.getValue().stream().map(val -> kv.getKey().toLowerCase() + ":" + val))
          .collect(Collectors.toSet());

    }

    protected boolean shouldRejectDuplicateHeaderKey() {
      return true;
    }

    public Set<String> getHeaderKeys() {
      return headers.keySet();
    }

    public Set<String> getFlatHeaders() {
      return flatHeaders;
    }

    @Override
    public String getName() {
      return ATTRIBUTE_NAME;
    }

  }
}

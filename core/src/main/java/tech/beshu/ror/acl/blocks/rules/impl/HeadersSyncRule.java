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

import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.RuleSettings;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class HeadersSyncRule extends SyncRule {

  private final Map<String, String> allowedHeaders;
  private final Settings settings;

  public HeadersSyncRule(Settings s) {
    this.allowedHeaders = s.getHeaders();
    this.settings = s;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    return rc.getHeaders().entrySet().containsAll(allowedHeaders.entrySet())
        ? MATCH
        : NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  public static class Settings implements RuleSettings {
    public static final String ATTRIBUTE_NAME = "headers";
    private final Map<String, String> headers;

    public Settings(Set<String> headersWithValue) {
      headersWithValue.stream().filter(x -> !x.contains(":")).findFirst().ifPresent(x -> {
        throw new SettingsMalformedException("Headers should be in the form 'Key:Value' (separated by a colon)");
      });
      this.headers = new HashMap(headersWithValue.size());
      for (String kv : headersWithValue) {
        String[] kva = kv.toLowerCase().split(":", 2);
        this.headers.put(kva[0], kva[1]);
      }
    }

    public Map<String, String> getHeaders() {
      return headers;
    }

    @Override
    public String getName() {
      return ATTRIBUTE_NAME;
    }

  }
}

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
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.LocalHostsRuleSettings;

import java.util.Optional;

public class LocalHostsSyncRule extends SyncRule {

  private final ESContext context;
  private final LocalHostsRuleSettings settings;
  private final LoggerShim logger;

  public LocalHostsSyncRule(LocalHostsRuleSettings s, ESContext context) {
    this.context = context;
    this.logger = context.logger(getClass());
    this.settings = s;
  }

  @Override
  public RuleExitResult match(__old_RequestContext rc) {
    String rcDestAddress = rc.getLocalAddress();
    return settings.getAllowedAddresses()
                   .stream()
                   .map(aa -> aa.getValue(rc))
                   .filter(Optional::isPresent)
                   .map(Optional::get)
                   .filter(v -> v.equals(rcDestAddress))
                   .findFirst()
                   .isPresent() ? MATCH : NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}

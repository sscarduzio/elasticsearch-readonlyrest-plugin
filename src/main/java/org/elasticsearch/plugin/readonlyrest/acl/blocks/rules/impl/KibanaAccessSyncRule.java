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
import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.es53x.ESContext;

import java.util.Optional;
import java.util.Set;

/**
 * Created by sscarduzio on 26/03/2016.
 */
public class KibanaAccessSyncRule extends SyncRule {

  public static MatcherWithWildcards RO = new MatcherWithWildcards(Sets.newHashSet(
      "indices:admin/exists",
      "indices:admin/mappings/fields/get*",
      "indices:admin/validate/query",
      "indices:data/read/field_stats",
      "indices:data/read/search",
      "indices:data/read/msearch",
      "indices:admin/get",
      "indices:admin/refresh*",
      "indices:data/read/*"
  ));
  public static MatcherWithWildcards RW = new MatcherWithWildcards(Sets.newHashSet(
      "indices:admin/create",
      "indices:admin/mapping/put",
      "indices:data/write/delete",
      "indices:data/write/index",
      "indices:data/write/update",
      "indices:data/write/bulk*"
  ));
  public static MatcherWithWildcards ADMIN = new MatcherWithWildcards(Sets.newHashSet(
      "cluster:admin/rradmin/*",
      "indices:data/write/*",
      "indices:admin/create"
  ));
  public static MatcherWithWildcards CLUSTER = new MatcherWithWildcards(Sets.newHashSet(
      "cluster:monitor/nodes/info",
      "cluster:monitor/health"
  ));

  private final Logger logger;
  private String kibanaIndex = ".kibana";
  private final Boolean canModifyKibana;
  private Boolean isAdmin = false;

  private KibanaAccessSyncRule(Settings s, ESContext context) throws RuleNotConfiguredException {
    logger = context.logger(getClass());

    String tmp = s.get(getKey());
    if (Strings.isNullOrEmpty(tmp)) {
      throw new RuleNotConfiguredException();
    }
    tmp = tmp.toLowerCase();

    if ("ro".equals(tmp)) {
      canModifyKibana = false;
    }
    else if ("rw".equals(tmp)) {
      canModifyKibana = true;
    }
    else if ("admin".equals(tmp)) {
      canModifyKibana = true;
      isAdmin = true;
    }
    else {
      throw new RuleConfigurationError("invalid configuration: use either 'ro', 'rw' or 'admin'. Found: + " + tmp, null);
    }

    kibanaIndex = ".kibana";
    tmp = s.get("kibana_index");
    if (!Strings.isNullOrEmpty(tmp)) {
      kibanaIndex = tmp;
    }
  }

  public static Optional<KibanaAccessSyncRule> fromSettings(Settings s, ESContext context) {
    try {
      return Optional.of(new KibanaAccessSyncRule(s, context));
    } catch (RuleNotConfiguredException ignored) {
      return Optional.empty();
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    Set<String> indices = rc.involvesIndices() ? rc.getIndices() : Sets.newHashSet();

    // Allow other actions if devnull is targeted to readers and writers
    if (indices.contains(".kibana-devnull")) {
      return MATCH;
    }

    // Any index, read op
    if (RO.match(rc.getAction()) || CLUSTER.match(rc.getAction())) {
      return MATCH;
    }

    boolean targetsKibana = indices.size() == 1 && indices.contains(kibanaIndex);

    // Kibana index, write op
    if (targetsKibana && canModifyKibana) {
      if (RO.match(rc.getAction()) || RW.match(rc.getAction())) {
        logger.debug("RW access to Kibana index: " + rc.getId());
        return MATCH;
      }
      logger.info("RW access to Kibana, but unrecognized action " + rc.getAction() + " reqID: " + rc.getId());
      return NO_MATCH;
    }

    if (indices.contains(".readonlyrest") && isAdmin && ADMIN.match(rc.getAction())) {
      return MATCH;
    }

    logger.debug("KIBANA ACCESS DENIED " + rc.getId());
    return NO_MATCH;
  }
}

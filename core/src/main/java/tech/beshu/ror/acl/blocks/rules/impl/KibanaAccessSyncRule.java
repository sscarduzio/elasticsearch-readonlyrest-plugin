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

import com.google.common.collect.Sets;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.commons.domain.Value;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.KibanaAccessRuleSettings;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Created by sscarduzio on 26/03/2016.
 */
public class KibanaAccessSyncRule extends SyncRule {
  private static final boolean ROR_KIBANA_METADATA_ENABLED =
    !"false".equalsIgnoreCase(System.getProperty("com.readonlyrest.kibana.metadata"));

  public static MatcherWithWildcards RO = new MatcherWithWildcards(Sets.newHashSet(
    "indices:admin/exists",
    "indices:admin/mappings/fields/get*",
    "indices:admin/mappings/get*",
    "indices:admin/validate/query",
    "indices:admin/get",
    "indices:admin/refresh*",
    "indices:data/read/*"
  ));
  public static MatcherWithWildcards RW = new MatcherWithWildcards(Sets.newHashSet(
    "indices:admin/create",
    "indices:admin/mapping/put",
    "indices:data/write/delete*",
    "indices:data/write/index",
    "indices:data/write/update*",
    "indices:data/write/bulk*",
    "indices:admin/template/*"
  ));
  
  public static MatcherWithWildcards ADMIN = new MatcherWithWildcards(Sets.newHashSet(
    "cluster:admin/rradmin/*",
    "indices:data/write/*", // <-- DEPRECATED!
    "indices:admin/create"
  ));
  public static MatcherWithWildcards CLUSTER = new MatcherWithWildcards(Sets.newHashSet(
    "cluster:monitor/nodes/info",
    "cluster:monitor/main",
    "cluster:monitor/health"
  ));

  private final LoggerShim logger;
  private final Value<String> kibanaIndex;
  private final Boolean canModifyKibana;
  private final KibanaAccessRuleSettings settings;
  private Boolean roStrict = false;
  private Boolean isAdmin = false;

  public KibanaAccessSyncRule(KibanaAccessRuleSettings s, ESContext context) {
    settings = s;
    logger = context.logger(getClass());

    switch (s.getKibanaAccess()) {
      case RO_STRICT:
        canModifyKibana = false;
        roStrict = true;
        break;
      case RO:
        canModifyKibana = false;
        break;
      case RW:
        canModifyKibana = true;
        break;
      case ADMIN:
        canModifyKibana = true;
        isAdmin = true;
        break;
      default:
        throw context.rorException("Unsupported kibana access option");
    }

    kibanaIndex = s.getKibanaIndex();
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    RuleExitResult res = doMatch(rc);
    if (ROR_KIBANA_METADATA_ENABLED && res.isMatch()) {
      rc.setResponseHeader("x-ror-kibana_access", settings.getKibanaAccess().name().toLowerCase());
    }
    return res;
  }

  private RuleExitResult doMatch(RequestContext rc) {
    Set<String> indices = rc.involvesIndices() ? rc.getIndices() : Sets.newHashSet();

    // Allow other actions if devnull is targeted to readers and writers
    if (indices.contains(".kibana-devnull")) {
      return MATCH;
    }

    // Any index, read op
    if (RO.match(rc.getAction()) || CLUSTER.match(rc.getAction())) {
      return MATCH;
    }

    String resolvedKibanaIndex = kibanaIndex.getValue(rc).orElse(".kibana");

    // Save UI state in discover & Short urls
    Pattern nonStrictAllowedPaths = Pattern.compile("^/@kibana_index/(url|config/.*/_create|index-pattern)/.*"
                                                      .replace("@kibana_index", resolvedKibanaIndex));

    boolean targetsKibana = indices.size() == 1 && indices.contains(resolvedKibanaIndex);

    // Ro non-strict cases to pass through
    if (
      targetsKibana && !roStrict && !canModifyKibana &&
        nonStrictAllowedPaths.matcher(rc.getUri()).find() &&
        rc.getAction().startsWith("indices:data/write/")
      ) {
      return MATCH;
    }

    // Kibana index, write op
    if (targetsKibana && canModifyKibana) {
      if (RO.match(rc.getAction()) || RW.match(rc.getAction()) || rc.getAction().startsWith("indices:data/write")) {
        logger.debug("RW access to Kibana index: " + rc.getId());
        return MATCH;
      }
      logger.info("RW access to Kibana, but unrecognized action " + rc.getAction() + " reqID: " + rc.getId());
      return NO_MATCH;
    }

    if ((indices.contains(".readonlyrest") || indices.isEmpty()) && isAdmin && ADMIN.match(rc.getAction())) {
      return MATCH;
    }

    logger.debug("KIBANA ACCESS DENIED " + rc.getId());
    return NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}

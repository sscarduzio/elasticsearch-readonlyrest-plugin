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
package tech.beshu.ror.es.actionlisteners;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.MultiSearchResponse;
import tech.beshu.ror.acl.blocks.BlockExitResult;
import tech.beshu.ror.acl.blocks.rules.impl.IndexLevelSecuritySyncRule;
import tech.beshu.ror.requestcontext.RequestContext;

public class IndexLevelSecuritySyncRuleActionListener extends RuleActionListener<IndexLevelSecuritySyncRule> {

  public IndexLevelSecuritySyncRuleActionListener() {
    super(IndexLevelSecuritySyncRule.class);
  }

  @Override
  protected boolean onResponse(BlockExitResult result,
                               RequestContext rc,
                               ActionRequest ar,
                               ActionResponse response,
                               IndexLevelSecuritySyncRule rule) {
    if (!rc.involvesIndices() || !rc.isReadRequest()) {
      return true;
    }
    if (response instanceof MultiSearchResponse) {
      MultiSearchResponse msr = (MultiSearchResponse) response;

    }
    return true;
  }

  @Override
  protected boolean onFailure(BlockExitResult result,
                              RequestContext rc,
                              ActionRequest ar,
                              Throwable e,
                              IndexLevelSecuritySyncRule rule) {
    return true;
  }
}

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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.base.CaseFormat;
import org.elasticsearch.plugin.readonlyrest.acl.requestcontext.RequestContext;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public abstract class SyncRule extends Rule {

  private final String KEY;

  public SyncRule() {
    super();
    KEY = mkKey(getClass());
  }

  public abstract RuleExitResult match(RequestContext rc);

  protected String mkKey(Class<? extends Rule> c) {
    return CaseFormat.LOWER_CAMEL.to(
        CaseFormat.LOWER_UNDERSCORE,
        c.getSimpleName().replace("SyncRule", "")
    );
  }

  @Override
  public String getKey() {
    return KEY;
  }

}

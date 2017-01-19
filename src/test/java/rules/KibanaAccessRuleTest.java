/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package rules;

import com.google.common.collect.Sets;
import junit.framework.TestCase;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.KibanaAccessRule;
import org.mockito.Mockito;

import java.util.Set;

import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class KibanaAccessRuleTest extends TestCase {

  class Conf {
    public String accessLevel = "ro";
    public String kibanaIndex = ".kibana";
  }

  class Found {
    public String action = null;
    public Set<String> indices = Sets.newHashSet(".kibana");
  }


  private RuleExitResult match(Conf configured, Found found) throws RuleNotConfiguredException {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(Conf configured, Found found, RequestContext rc) throws RuleNotConfiguredException {
    when(rc.involvesIndices()).thenReturn(false);
    when(rc.getIndices()).thenReturn(found.indices);
    when(rc.getAction()).thenReturn(found.action);
    when(rc.involvesIndices()).thenReturn(false);

    Rule r = new KibanaAccessRule(Settings.builder()
        .put("kibana_access", configured.accessLevel)
        .put("kibana_index", configured.kibanaIndex)
        .build());

    RuleExitResult res = r.match(rc);
    rc.commit();
    return res;
  }

  public void testRO() throws RuleNotConfiguredException {
    Conf conf = new Conf();
    conf.accessLevel = "ro";
    conf.kibanaIndex = ".kibana";
    Found found = new Found();
    found.action = "";
    found.indices = Sets.newHashSet(".kibana");
    RuleExitResult res = match(conf, found);
    assertFalse(res.isMatch());
  }
}

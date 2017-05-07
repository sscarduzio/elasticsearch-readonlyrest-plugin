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

package org.elasticsearch.plugin.readonlyrest.acl.blocks;

import junit.framework.TestCase;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RulesFactory;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.UserRuleFactory;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.DefinitionsFactory;
import org.elasticsearch.plugin.readonlyrest.settings.AuthMethodCreatorsRegistry;
import org.elasticsearch.plugin.readonlyrest.settings.BlockSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ProxyAuthConfigSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.utils.esdependent.MockedESContext;
import org.junit.Assert;

import java.util.Iterator;
import java.util.List;

public class BlockTest extends TestCase {

  public void testRulesShallFollowAuthInspectMutateOrder() {
    UserRuleFactory userRuleFactory = new UserRuleFactory(MockedESContext.INSTANCE);
    DefinitionsFactory definitionsFactory = new DefinitionsFactory(userRuleFactory);
    RulesFactory rulesFactory = new RulesFactory(definitionsFactory, userRuleFactory, MockedESContext.INSTANCE);
    Block block = new Block(
        BlockSettings.from(
            RawSettings.fromString("" +
                "name: Dummy block\n" +
                "type: allow\n" +
                "proxy_auth: \"*\"\n" +
                "indices_rewrite: [\"needle\", \"replacement\"]\n" +
                "indices: [\"allowed-index\"]"
            ),
            new AuthMethodCreatorsRegistry(ProxyAuthConfigSettingsCollection.from(RawSettings.empty())),
            null, null,
            null, null
        ),
        rulesFactory,
        MockedESContext.INSTANCE
    );

    List<AsyncRule> rules = block.getRules();
    Iterator<AsyncRule> it = rules.iterator();
    AsyncRule auth = it.next();
    AsyncRule inspect = it.next();
    AsyncRule mutate = it.next();

    Assert.assertEquals("proxy_auth", auth.getKey());
    Assert.assertEquals("indices", inspect.getKey());
    Assert.assertEquals("indices_rewrite", mutate.getKey());
  }

}

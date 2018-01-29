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

package tech.beshu.ror.acl.blocks;

import org.junit.Assert;
import org.junit.Test;
import tech.beshu.ror.TestUtils;
import tech.beshu.ror.acl.blocks.rules.AsyncRule;
import tech.beshu.ror.acl.blocks.rules.RulesFactory;
import tech.beshu.ror.acl.blocks.rules.UserRuleFactory;
import tech.beshu.ror.acl.definitions.DefinitionsFactory;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.mocks.MockedACL;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.settings.AuthMethodCreatorsRegistry;
import tech.beshu.ror.settings.BlockSettings;
import tech.beshu.ror.settings.definitions.ProxyAuthDefinitionSettingsCollection;

import java.util.Iterator;
import java.util.List;

public class BlockTest {

  @Test
  public void testRulesShallFollowAuthInspectMutateOrder() {
    UserRuleFactory userRuleFactory = new UserRuleFactory(MockedESContext.INSTANCE, MockedACL.getMock());
    DefinitionsFactory definitionsFactory = new DefinitionsFactory(MockedESContext.INSTANCE, MockedACL.getMock());
    RulesFactory rulesFactory = new RulesFactory(definitionsFactory, userRuleFactory, MockedESContext.INSTANCE);
    Block block = new Block(
      BlockSettings.from(
        TestUtils.fromYAMLString("" +
                                   "name: Dummy block\n" +
                                   "type: allow\n" +
                                   "proxy_auth: \"*\"\n" +
                                   "indices: [\"allowed-index\"]"
        ),
        new AuthMethodCreatorsRegistry(ProxyAuthDefinitionSettingsCollection.from(RawSettings.empty()), null, null),
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

    Assert.assertEquals("proxy_auth", auth.getKey());
    Assert.assertEquals("indices", inspect.getKey());
  }

}

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

import java.util.Iterator;
import java.util.Set;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;

import com.google.common.collect.Lists;

import org.junit.Assert;
import junit.framework.TestCase;

public class BlockTest extends TestCase {

	public void testRulesShallFollowAuthInspectMutateOrder() {
		Settings settings = Settings.builder()
		                        .put("name", "Dummy block")
		                        .put("type", "allow")
		                        .put("proxy_auth", "*")
		                        .putArray("indices_rewrite", "needle", "replacement")
		                        .putArray("indices", "allowed-index")
		                        .build();
		Block block = new Block(settings, Lists.newArrayList(), Lists.newArrayList(),
		                        null, null, null);

		Set<AsyncRule> rules = block.getRules();
		Iterator<AsyncRule> it = rules.iterator();
		AsyncRule auth = it.next();
		AsyncRule inspect = it.next();
		AsyncRule mutate = it.next();

		Assert.assertEquals("proxy_auth", auth.getKey());
		Assert.assertEquals("indices", inspect.getKey());
		Assert.assertEquals("indices_rewrite", mutate.getKey());
	}

}

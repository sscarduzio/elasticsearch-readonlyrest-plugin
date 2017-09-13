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

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.mocks.MockedESContext;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeySha512RuleSettings;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Base64;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Created by samy-orange on 03/07/2017.
 */

public class AuthKeySha512RuleTests {

  private RuleExitResult match(String configured, String found) {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(String configured, String found, RequestContext rc) {
    when(rc.getHeaders()).thenReturn(ImmutableMap.of("Authorization", found));

    SyncRule r = new AuthKeySha512SyncRule(new AuthKeySha512RuleSettings(configured), MockedESContext.INSTANCE);

    return r.match(rc);
  }

  @Test
  public void testSimple() {
    RuleExitResult res = match(
        "3586d5752240fd09e967383d3f1bad025bbc6953ba7c6d2135670631b4e326fee0cc8bd81addb9f6de111b9c380505b5ea0531598c21b0906d8e726f24e0dbe2",
        "Basic " + Base64.getEncoder().encodeToString("logstash:logstash".getBytes())
    );
    assertTrue(res.isMatch());
  }
}

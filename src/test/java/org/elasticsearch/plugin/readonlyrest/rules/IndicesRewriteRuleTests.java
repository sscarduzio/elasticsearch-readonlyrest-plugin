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

package org.elasticsearch.plugin.readonlyrest.rules;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import junit.framework.TestCase;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesRewriteSyncRule;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContextImpl;
import org.elasticsearch.plugin.readonlyrest.utils.esdependent.MockedESContext;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

@RunWith(MockitoJUnitRunner.class)
public class IndicesRewriteRuleTests extends TestCase {

  @Captor
  private ArgumentCaptor<Set<String>> argumentCaptor;

  private void match(List<String> configured, List<String> foundInRequest, List<String> expected) throws RuleNotConfiguredException {
    Collections.sort(foundInRequest);
    Collections.sort(expected);
    RequestContextImpl rc = Mockito.mock(RequestContextImpl.class);
    Set<String> foundSet = Sets.newHashSet();
    foundSet.addAll(foundInRequest);
    when(rc.getIndices()).thenReturn(foundSet);
    when(rc.involvesIndices()).thenReturn(true);
    when(rc.getExpandedIndices()).thenReturn(foundSet);
    when(rc.isReadRequest()).thenReturn(true);
    when(rc.getLoggedInUser()).thenReturn(Optional.of(new LoggedUser("simone")));

    SyncRule r = IndicesRewriteSyncRule.fromSettings(
        Settings.builder()
            .putArray("indices_rewrite", configured).build(),
        MockedESContext.INSTANCE
    ).get();

    RuleExitResult res = r.match(rc);
    rc.commit();
    verify(rc).setIndices(argumentCaptor.capture());

    String expectedJ = Joiner.on(",").join(expected);
    List<String> argumentsAsList = Lists.newArrayList(argumentCaptor.getValue().iterator());
    Collections.sort(argumentsAsList);
    String capturedJ = Joiner.on(",").join(argumentsAsList);
    assertEquals(expectedJ, capturedJ);
    assertTrue(res.isMatch());
  }

  @Test
  public void testNOOP() throws RuleNotConfiguredException {
    match(
        Arrays.asList("public-asd", "replacement"),
        Arrays.asList("x"),
        Arrays.asList("x")
    );
  }

  @Test
  public void testSimpleIndex() throws RuleNotConfiguredException {
    match(
        Arrays.asList("public-asd", "replacement"),
        Arrays.asList("public-asd"),
        Arrays.asList("replacement")
    );
  }

  @Test
  public void testRegexIndex() throws RuleNotConfiguredException {
    match(
        Arrays.asList("public-.*", "replacement"),
        Arrays.asList("public-asd"),
        Arrays.asList("replacement")
    );
  }

  @Test
  public void testBigRegexIndex() throws RuleNotConfiguredException {
    match(
        Arrays.asList("^public-.*$", "replacement"),
        Arrays.asList("public-asd"),
        Arrays.asList("replacement")
    );
  }

  @Test
  public void testBigRegexIndexMultiIndex() throws RuleNotConfiguredException {
    match(
        Arrays.asList("^public-.*$", "replacement"),
        Arrays.asList("public-asd", "quack"),
        Arrays.asList("replacement", "quack")
    );
  }

  @Test
  public void testBigRegexIndexMultiIndexMultiRule() throws RuleNotConfiguredException {
    match(
        Arrays.asList("^public-.*$", ".*ack", "replacement"),
        Arrays.asList("public-asd", "quack", "ack"),
        Arrays.asList("replacement")
    );
  }

  @Test
  public void testNOOPBigRegexIndexMultiIndexMultiRule() throws RuleNotConfiguredException {
    match(
        Arrays.asList("^public-.*$", ".*ack", "replacement"),
        Arrays.asList("x", "y", "z"),
        Arrays.asList("x", "y", "z")
    );
  }

  @Test
  public void testBigRegexIndexMultiIndexMultiRuleWithOutlier() throws RuleNotConfiguredException {
    match(
        Arrays.asList("^public-.*$", ".*ack", "replacement"),
        Arrays.asList("public-asd", "quack", "ack", "outlier"),
        Arrays.asList("replacement", "outlier")
    );
  }

  @Test
  public void testKibanaAndLogstash() throws RuleNotConfiguredException {
    match(
        Arrays.asList("(^\\.kibana.*|^logstash.*)", "$1_user1"),
        Arrays.asList(".kibana", "logstash-2001-01-01"),
        Arrays.asList(".kibana_user1", "logstash-2001-01-01_user1")
    );
  }

  @Test
  public void testUserReplacement() throws RuleNotConfiguredException {
    match(
        Arrays.asList("(^\\.kibana.*|^logstash.*)", "$1_@user"),
        Arrays.asList(".kibana", "logstash-2001-01-01"),
        Arrays.asList(".kibana_simone", "logstash-2001-01-01_simone")
    );
  }

}

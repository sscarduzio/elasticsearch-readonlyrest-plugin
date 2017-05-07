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
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesRewriteSyncRule;
import org.elasticsearch.plugin.readonlyrest.settings.rules.IndicesRewriteRuleSettings;
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

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

@RunWith(MockitoJUnitRunner.class)
public class IndicesRewriteRuleTests extends TestCase {

  @Captor
  private ArgumentCaptor<Set<String>> argumentCaptor;

  private void match(List<String> configured, List<String> foundInRequest, List<String> expected) {
    Collections.sort(foundInRequest);
    Collections.sort(expected);
    RequestContext rc = Mockito.mock(RequestContext.class);
    Set<String> foundSet = Sets.newHashSet();
    foundSet.addAll(foundInRequest);
    when(rc.getIndices()).thenReturn(foundSet);
    when(rc.involvesIndices()).thenReturn(true);
    when(rc.getExpandedIndices()).thenReturn(foundSet);
    when(rc.isReadRequest()).thenReturn(true);

    when(rc.getLoggedInUser()).thenReturn(Optional.of(new LoggedUser("simone")));
    when(rc.resolveVariable(anyString())).thenAnswer(i ->
       Optional.of(
         ((String) i.getArguments()[0])
                     .replaceAll("@\\{user}", "simone")
       )
    );

    SyncRule r = new IndicesRewriteSyncRule(IndicesRewriteRuleSettings.from(configured), MockedESContext.INSTANCE);

    RuleExitResult res = r.match(rc);
    verify(rc).setIndices(argumentCaptor.capture());

    String expectedJ = Joiner.on(",").join(expected);
    List<String> argumentsAsList = Lists.newArrayList(argumentCaptor.getValue().iterator());
    Collections.sort(argumentsAsList);
    String capturedJ = Joiner.on(",").join(argumentsAsList);
    assertEquals(expectedJ, capturedJ);
    assertTrue(res.isMatch());
  }

  @Test
  public void testNOOP() {
    match(
        Arrays.asList("public-asd", "replacement"),
        Arrays.asList("x"),
        Arrays.asList("x")
    );
  }

  @Test
  public void testSimpleIndex() {
    match(
        Arrays.asList("public-asd", "replacement"),
        Arrays.asList("public-asd"),
        Arrays.asList("replacement")
    );
  }

  @Test
  public void testRegexIndex() {
    match(
        Arrays.asList("public-.*", "replacement"),
        Arrays.asList("public-asd"),
        Arrays.asList("replacement")
    );
  }

  @Test
  public void testBigRegexIndex() {
    match(
        Arrays.asList("^public-.*$", "replacement"),
        Arrays.asList("public-asd"),
        Arrays.asList("replacement")
    );
  }

  @Test
  public void testBigRegexIndexMultiIndex() {
    match(
        Arrays.asList("^public-.*$", "replacement"),
        Arrays.asList("public-asd", "quack"),
        Arrays.asList("replacement", "quack")
    );
  }

  @Test
  public void testBigRegexIndexMultiIndexMultiRule() {
    match(
        Arrays.asList("^public-.*$", ".*ack", "replacement"),
        Arrays.asList("public-asd", "quack", "ack"),
        Arrays.asList("replacement")
    );
  }

  @Test
  public void testNOOPBigRegexIndexMultiIndexMultiRule() {
    match(
        Arrays.asList("^public-.*$", ".*ack", "replacement"),
        Arrays.asList("x", "y", "z"),
        Arrays.asList("x", "y", "z")
    );
  }

  @Test
  public void testBigRegexIndexMultiIndexMultiRuleWithOutlier() {
    match(
        Arrays.asList("^public-.*$", ".*ack", "replacement"),
        Arrays.asList("public-asd", "quack", "ack", "outlier"),
        Arrays.asList("replacement", "outlier")
    );
  }

  @Test
  public void testKibanaAndLogstash() {
    match(
        Arrays.asList("(^\\.kibana.*|^logstash.*)", "$1_user1"),
        Arrays.asList(".kibana", "logstash-2001-01-01"),
        Arrays.asList(".kibana_user1", "logstash-2001-01-01_user1")
    );
  }

  @Test
  public void testUserReplacement() {
    match(
        Arrays.asList("(^\\.kibana.*|^logstash.*)", "$1_@{user}"),
        Arrays.asList(".kibana", "logstash-2001-01-01"),
        Arrays.asList(".kibana_simone", "logstash-2001-01-01_simone")
    );
  }

}

package org.elasticsearch.plugin.readonlyrest.other;

import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RulesComparator;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.KibanaAccessSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.RoleBasedAuthorizationAsyncRule;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

public class RuleComparatorTests {

  private Rule authorization = Mockito.mock(RoleBasedAuthorizationAsyncRule.class);
  private Rule authentication = Mockito.mock(LdapAuthAsyncRule.class);
  private Rule other1 = indicesRule();
  private Rule other2 = kibanaAccessRule();

  @Test
  public void testRuleOrder() {
    List<Rule> sorted = Lists.newArrayList(authorization, other2, other1, authentication).stream()
        .sorted(RulesComparator.INSTANCE)
        .collect(Collectors.toList());
    assertEquals(Lists.newArrayList(other1, other2, authentication, authorization), sorted);
  }

  private Rule indicesRule() {
    IndicesSyncRule rule = Mockito.mock(IndicesSyncRule.class);
    when(rule.getKey()).thenReturn("Indices");
    return rule;
  }

  private Rule kibanaAccessRule() {
    KibanaAccessSyncRule rule = Mockito.mock(KibanaAccessSyncRule.class);
    when(rule.getKey()).thenReturn("KibanaAccess");
    return rule;
  }
}

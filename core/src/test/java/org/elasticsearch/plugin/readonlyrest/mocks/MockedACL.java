package org.elasticsearch.plugin.readonlyrest.mocks;

import org.elasticsearch.plugin.readonlyrest.acl.ACL;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.UserRuleFactory;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.DefinitionsFactory;
import org.mockito.Mockito;

/**
 * Created by sscarduzio on 03/06/2017.
 */
public class MockedACL {
  private static ACL INSTANCE;

  public static final ACL getMock()  {
    if(INSTANCE != null){
      return INSTANCE;
    }
    ACL acl = Mockito.mock(ACL.class);
    Mockito.when(acl.getUserRuleFactory()).thenReturn(new UserRuleFactory(MockedESContext.INSTANCE, acl));
    Mockito.when(acl.getDefinitionsFactory()).thenReturn(new DefinitionsFactory(MockedESContext.INSTANCE, acl));
    INSTANCE = acl;
    return acl;
  }

}

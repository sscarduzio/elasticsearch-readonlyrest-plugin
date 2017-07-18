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

  public static final ACL getMock() {
    if (INSTANCE != null) {
      return INSTANCE;
    }
    ACL acl = Mockito.mock(ACL.class);
    Mockito.when(acl.getUserRuleFactory()).thenReturn(new UserRuleFactory(MockedESContext.INSTANCE, acl));
    Mockito.when(acl.getDefinitionsFactory()).thenReturn(new DefinitionsFactory(MockedESContext.INSTANCE, acl));
    INSTANCE = acl;
    return acl;
  }

}

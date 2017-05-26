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
package org.elasticsearch.plugin.readonlyrest.utils;

import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRuleAdapter;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthenticationDecorator;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthorizationDecorator;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;

public class RulesUtils {

  public static Class<? extends Rule> classOfRule(Rule rule) {
    if (rule instanceof CachedAsyncAuthenticationDecorator) {
      return tryAsyncAdapterUnpack(((CachedAsyncAuthenticationDecorator) rule).getUnderlying());
    } else if (rule instanceof CachedAsyncAuthorizationDecorator) {
      return tryAsyncAdapterUnpack(((CachedAsyncAuthorizationDecorator) rule).getUnderlying());
    } else {
      return tryAsyncAdapterUnpack(rule);
    }
  }

  private static Class<? extends Rule> tryAsyncAdapterUnpack(Rule rule) {
    return rule instanceof AsyncRuleAdapter
        ? ((AsyncRuleAdapter) rule).getUnderlying().getClass()
        : rule.getClass();
  }

}

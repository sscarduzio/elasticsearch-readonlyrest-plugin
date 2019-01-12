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
package tech.beshu.ror.utils;

import tech.beshu.ror.unit.acl.blocks.rules.AsyncRule;
import tech.beshu.ror.unit.acl.blocks.rules.AsyncRuleAdapter;
import tech.beshu.ror.unit.acl.blocks.rules.CachedAsyncAuthenticationDecorator;
import tech.beshu.ror.unit.acl.blocks.rules.CachedAsyncAuthorizationDecorator;
import tech.beshu.ror.unit.acl.blocks.rules.__old_Rule;

import java.util.Set;
import java.util.stream.Collectors;

public class RulesUtils {

  public static Class<? extends __old_Rule> classOfRule(__old_Rule rule) {
    if (rule instanceof CachedAsyncAuthenticationDecorator) {
      return tryAsyncAdapterUnpack(((CachedAsyncAuthenticationDecorator) rule).getUnderlying());
    }
    else if (rule instanceof CachedAsyncAuthorizationDecorator) {
      return tryAsyncAdapterUnpack(((CachedAsyncAuthorizationDecorator) rule).getUnderlying());
    }
    else {
      return tryAsyncAdapterUnpack(rule);
    }
  }

  private static Class<? extends __old_Rule> tryAsyncAdapterUnpack(__old_Rule rule) {
    return rule instanceof AsyncRuleAdapter
      ? ((AsyncRuleAdapter) rule).getUnderlying().getClass()
      : rule.getClass();
  }


  public static Set<Class<?>> ruleHasPhantomTypes(AsyncRule rule, Set<Class<?>> phantomTypes) {
    Class<?> tmpRuleClass = rule.getClass();
    if (rule instanceof AsyncRuleAdapter) {
      tmpRuleClass = ((AsyncRuleAdapter) rule).getUnderlying().getClass();
    }
    Class<?> ruleClass = tmpRuleClass;
    return phantomTypes.stream().filter(pt -> pt.isAssignableFrom(ruleClass)).collect(Collectors.toSet());
  }

}

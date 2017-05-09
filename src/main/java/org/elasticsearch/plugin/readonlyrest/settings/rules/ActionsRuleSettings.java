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
package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.util.Set;

public class ActionsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "actions";

  private final Set<String> actions;

  public static ActionsRuleSettings from(Set<String> indices) {
    return new ActionsRuleSettings(indices);
  }

  private ActionsRuleSettings(Set<String> actions) {
    this.actions = actions;
  }

  public Set<String> getActions() {
    return actions;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}

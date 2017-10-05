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

package tech.beshu.ror.acl.blocks.rules;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class RuleExitResult {
  private final Rule condition;
  private final Boolean match;

  public RuleExitResult(Boolean match, Rule condition) {
    this.match = match;
    this.condition = condition;
  }

  public Boolean isMatch() {
    return match;
  }

  public Rule getCondition() {
    return condition;
  }

  @Override
  public String toString() {
    String condString = condition != null ? condition.getKey() : "none";
    return "{ matched: " + match + ", condition: " + condString + " }";
  }
}

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

package tech.beshu.ror.acl.blocks;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class __old_BlockExitResult {
  private final __old_Block block;
  private final boolean match;

  private __old_BlockExitResult(__old_Block block, Boolean match) {
    this.block = block;
    this.match = match;
  }

  public static __old_BlockExitResult match(__old_Block block) {
    return new __old_BlockExitResult(block, true);
  }

  public static __old_BlockExitResult noMatch() {
    return new __old_BlockExitResult(null, false);
  }

  public boolean isMatch() {
    // Cover the case of rule == null to allow the creation of a dummy NO_MATCH rule result
    return block != null && match;
  }

  public __old_Block getBlock() {
    return block;
  }

  @Override
  public String toString() {
    return "{ block=" + (block == null ? "<none>" : block.getName()) + ", match=" + isMatch() + " }";
  }
}

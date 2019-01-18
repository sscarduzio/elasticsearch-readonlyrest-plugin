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

package tech.beshu.ror.commons.shims.es;

import tech.beshu.ror.acl.domain.__old_Value;

public interface __old_ACLHandler {
  void onForbidden();

  void onAllow(Object blockExitResult, __old_Value.__old_VariableResolver rc);

  boolean isNotFound(Throwable t);

  void onNotFound(Throwable t);

  void onErrored(Throwable t);
}

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
package tech.beshu.ror.unit.acl.blocks.rules.utils

import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeResolvableVariableCreator, RuntimeSingleResolvableVariable}
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}

object IndexNameRuntimeResolvableVariable{
  def create(value: String): RuntimeSingleResolvableVariable[IndexName] = {
    implicit val provider: EnvVarsProvider = OsEnvVarsProvider
    RuntimeResolvableVariableCreator
      .createSingleResolvableVariableFrom[IndexName](value.nonempty)(AlwaysRightConvertible.from(IndexName.apply))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create IndexName Value from $value"))
  }
}

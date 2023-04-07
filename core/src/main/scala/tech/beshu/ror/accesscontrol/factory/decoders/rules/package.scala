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
package tech.beshu.ror.accesscontrol.factory.decoders

import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions

package object rules {

  implicit class OptionalImpersonatorDefinitionOps(val impersonatorsDefinitions: Option[Definitions[ImpersonatorDef]]) {

    def toImpersonation(mocksProvider: MocksProvider): Impersonation = {
      impersonatorsDefinitions match {
        case Some(definitions) =>
          Impersonation.Enabled(ImpersonationSettings(definitions.items, mocksProvider))
        case None =>
          Impersonation.Disabled
      }

    }
  }
}

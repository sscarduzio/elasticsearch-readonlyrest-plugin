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
package tech.beshu.ror.unit.acl.blocks.rules.kibana

import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.Permitted
import tech.beshu.ror.accesscontrol.blocks.metadata.{BlockMetadata, KibanaPolicy}
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaIndexRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.domain.KibanaIndexName
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.unit.acl.blocks.rules.utils.KibanaIndexNameRuntimeResolvableVariable
import tech.beshu.ror.utils.TestsUtils.*

class KibanaIndexRuleTests
  extends AnyWordSpec with Inside with BlockContextAssertion {

  "A KibanaIndexRule" should {
    "always match" should {
      "set kibana index if can be resolved" in {
        val rule = new KibanaIndexRule(KibanaIndexRule.Settings(indexNameValueFrom("kibana_index")))
        val requestContext = MockRequestContext.indices
        val blockContext = UserMetadataRequestBlockContext(mock[Block], requestContext, BlockMetadata.empty, Set.empty, List.empty)

        val result = rule.check(blockContext).runSyncUnsafe()

        inside(result) {
          case Permitted(blockContext: UserMetadataRequestBlockContext) =>
            assertBlockContext(blockContext)(
              kibanaPolicy = Some(KibanaPolicy.default.copy(templateIndex = Some(kibanaIndexName("kibana_index"))))
            )
        }
      }
      "not set kibana index if cannot be resolved" in {
        val rule = new KibanaIndexRule(KibanaIndexRule.Settings(indexNameValueFrom("kibana_index_of_@{user}")))
        val requestContext = MockRequestContext.indices
        val blockContext = UserMetadataRequestBlockContext(mock[Block], requestContext, BlockMetadata.empty, Set.empty, List.empty)

        val result = rule.check(blockContext).runSyncUnsafe()

        inside(result) {
          case Permitted(blockContext: UserMetadataRequestBlockContext) =>
            assertBlockContext(blockContext)()
        }
      }
    }
  }

  private def indexNameValueFrom(value: String): RuntimeSingleResolvableVariable[KibanaIndexName] =
    KibanaIndexNameRuntimeResolvableVariable.create(value)
}

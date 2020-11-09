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
package tech.beshu.ror.unit.acl.blocks.rules

import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaIndexRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.unit.acl.blocks.rules.utils.IndexNameRuntimeResolvableVariable
import tech.beshu.ror.utils.TestsUtils._

class KibanaIndexRuleTests extends WordSpec with MockFactory {

  "A KibanaIndexRule" should {
    "always match" should {
      "set kibana index if can be resolved" in {
        val rule = new KibanaIndexRule(KibanaIndexRule.Settings(indexNameValueFrom("kibana_index")))
        val requestContext = MockRequestContext.indices
        val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.empty, Set.empty)
        rule.check(blockContext).runSyncStep shouldBe Right(Fulfilled(
          CurrentUserMetadataRequestBlockContext(
            requestContext,
            UserMetadata.empty.withKibanaIndex(IndexName("kibana_index".nonempty)),
            Set.empty)
        ))
      }
      "not set kibana index if cannot be resolved" in {
        val rule = new KibanaIndexRule(KibanaIndexRule.Settings(indexNameValueFrom("kibana_index_of_@{user}")))
        val requestContext = MockRequestContext.indices
        val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.empty, Set.empty)
        rule.check(blockContext).runSyncStep shouldBe Right(Fulfilled(
          CurrentUserMetadataRequestBlockContext(
            requestContext,
            UserMetadata.empty,
            Set.empty)
        ))
      }
    }
  }

  private def indexNameValueFrom(value: String): RuntimeSingleResolvableVariable[IndexName] =
    IndexNameRuntimeResolvableVariable.create(value)
}

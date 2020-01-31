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
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaTemplateIndexRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.unit.acl.blocks.rules.utils.IndexNameRuntimeResolvableVariable
import tech.beshu.ror.utils.TestsUtils._

class KibanaTemplateIndexRuleTests
  extends WordSpec
    with MockFactory {

  import org.scalatest.Matchers._

  "set kibana template index if can be resolved" in {
    val rule = new KibanaTemplateIndexRule(KibanaTemplateIndexRule.Settings(indexNameValueFrom("kibana_template_index")))
    val requestContext = MockRequestContext.default
    val blockContext = mock[BlockContext]
    val newBlockContext = mock[BlockContext]
    (blockContext.withKibanaTemplateIndex _).expects(IndexName("kibana_template_index".nonempty)).returning(newBlockContext)
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(newBlockContext))
  }

  private def indexNameValueFrom(value: String): RuntimeSingleResolvableVariable[IndexName] =
    IndexNameRuntimeResolvableVariable.create(value)
}

package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptyList
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.ResponseFieldsRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, FilteredResponseFields}
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering.{AccessMode, ResponseField, ResponseFieldsRestrictions}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class ResponseFieldsRuleTests extends WordSpec with MockFactory {
  "A ResponseFields rule" should {
    "add appropriate response transformation to block context" when {
      "whitelist mode is used" in {
        assertMatchRule(NonEmptyList.of("field1", "field2"), AccessMode.Whitelist)
      }
      "blacklist mode is used" in {
        assertMatchRule(NonEmptyList.of("blacklistedfield1", "blacklistedfield2"), AccessMode.Blacklist)
      }
    }
  }

  private def assertMatchRule(fields: NonEmptyList[String], mode: AccessMode) = {
    val resolvedFields = fields.map(field => AlreadyResolved(ResponseField(field.nonempty).nel))
    val rule = new ResponseFieldsRule(ResponseFieldsRule.Settings(UniqueNonEmptyList.fromNonEmptyList(resolvedFields), mode))
    val requestContext = MockRequestContext.indices
    val blockContext = GeneralIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

    rule.check(blockContext).runSyncStep shouldBe Right(Fulfilled(
      BlockContext.GeneralIndexRequestBlockContext(
        requestContext,
        UserMetadata.empty,
        Set.empty,
        FilteredResponseFields(ResponseFieldsRestrictions(UniqueNonEmptyList.fromNonEmptyList(resolvedFields.map(_.value.head)), mode)) :: Nil,
        Set.empty,
        Set.empty
      )
    ))
  }
}

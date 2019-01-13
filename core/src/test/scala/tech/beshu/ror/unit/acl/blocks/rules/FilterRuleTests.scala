package tech.beshu.ror.unit.acl.blocks.rules

import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.commons.aDomain.Header.Name
import tech.beshu.ror.commons.aDomain.{Filter, Header}
import tech.beshu.ror.commons.domain.{LoggedUser, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.unit.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.unit.acl.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.unit.acl.blocks.{BlockContext, Value}


class FilterRuleTests extends WordSpec with MockFactory {

  "A FilterRuleTests" should {
    "match and set transient filter" when {
      "filter value is const" in {
        val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}"
        val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
        val requestContext = MockRequestContext.default.copy(isReadOnlyRequest = false)
        val blockContext = mock[BlockContext]
        val newBlockContext = mock[BlockContext]
        (blockContext.addContextHeader _)
          .expects(Header(Name.transientFilter, "rO0ABXNyACx0ZWNoLmJlc2h1LnJvci5jb21tb25zLnV0aWxzLkZpbHRlclRyYW5zaWVudITzas9SBWxbAgABTAAHX2ZpbHRlcnQAEkxqYXZhL2xhbmcvU3RyaW5nO3hwdAA3eyJib29sIjp7Im11c3QiOlt7InRlcm0iOnsiQ291bnRyeSI6eyJ2YWx1ZSI6IlVLIn19fV19fQ=="))
          .returning(newBlockContext)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(newBlockContext))
      }
      "filter value can be resolved" in {
        val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"User\":{\"value\":\"@{user}\"}}}]}}"
        val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
        val requestContext = MockRequestContext.default.copy(isReadOnlyRequest = false)
        val blockContext = mock[BlockContext]
        (blockContext.loggedUser _).expects().returning(Some(LoggedUser(User.Id("bob"))))
        val newBlockContext = mock[BlockContext]
        (blockContext.addContextHeader _)
          .expects(Header(Name.transientFilter, "rO0ABXNyACx0ZWNoLmJlc2h1LnJvci5jb21tb25zLnV0aWxzLkZpbHRlclRyYW5zaWVudITzas9SBWxbAgABTAAHX2ZpbHRlcnQAEkxqYXZhL2xhbmcvU3RyaW5nO3hwdAA1eyJib29sIjp7Im11c3QiOlt7InRlcm0iOnsiVXNlciI6eyJ2YWx1ZSI6ImJvYiJ9fX1dfX0="))
          .returning(newBlockContext)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(newBlockContext))
      }
    }
    "not match" when {
      "filter value cannot be resolved" in {
        val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"User\":{\"value\":\"@{user}\"}}}]}}"
        val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
        val requestContext = MockRequestContext.default.copy(isReadOnlyRequest = false)
        val blockContext = mock[BlockContext]
        (blockContext.loggedUser _).expects().returning(None)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.Rejected)
      }
      "request is read only" in {
        val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}"
        val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
        val requestContext = MockRequestContext.default.copy(isReadOnlyRequest = true)
        val blockContext = mock[BlockContext]
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.Rejected)
      }
    }
  }

  private def filterValueFrom(value: String): Value[Filter] = {
    Value
      .fromString(value, rv => Right(Filter(rv.value)))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create Filter Value from $value"))
  }
}

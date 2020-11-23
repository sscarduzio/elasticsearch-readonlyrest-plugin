package tech.beshu.ror.unit.acl.blocks.rules

import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule.InternalApiAccess.{Allow, Forbid}
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.domain.{Action, IndexName, RorAuditIndexTemplate, RorConfigurationIndex}
import tech.beshu.ror.mocks.MockRequestContext

import scala.concurrent.duration._
import scala.language.postfixOps

class RorInternalApiRuleTests extends WordSpec with Inside {

  "A RorInternalApiRule" when {
    "access is set to 'allow'" should {
      "allow request" when {
        "it's one of ROR actions request" when {
          "it's a ROR config request" in {
            val rule = new RorInternalApiRule(Settings(
              access = Allow,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = None
            ))
            val requestContext = MockRequestContext.nonIndices.copy(action = Action.rorConfigAction)
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            inside(result) { case Fulfilled(_) => }
          }
          "it's a legacy ROR config request" in {
            val rule = new RorInternalApiRule(Settings(
              access = Allow,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = None
            ))
            val requestContext = MockRequestContext.nonIndices.copy(action = Action.rorOldConfigAction)
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            inside(result) { case Fulfilled(_) => }
          }
          "it's a ROR audit event request" in {
            val rule = new RorInternalApiRule(Settings(
              access = Allow,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = None
            ))
            val requestContext = MockRequestContext.nonIndices.copy(action = Action.rorAuditEventAction)
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            inside(result) { case Fulfilled(_) => }
          }
        }
        "it's a request which involves indices" which {
          "is related to ROR config index" when {
            "full index name is used" in {
              val rule = new RorInternalApiRule(Settings(
                access = Allow,
                configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
                indexAuditTemplate = None
              ))
              val requestContext = MockRequestContext.indices.copy(filteredIndices = Set(IndexName(".readonlyrest")))
              val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

              inside(result) { case Fulfilled(_) => }
            }
            "index name with wildcard is used" in {
              val rule = new RorInternalApiRule(Settings(
                access = Allow,
                configurationIndex = RorConfigurationIndex(IndexName(".ror")),
                indexAuditTemplate = None
              ))
              val requestContext = MockRequestContext.indices.copy(filteredIndices = Set(IndexName(".r*")))
              val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

              inside(result) { case Fulfilled(_) => }
            }
          }
          "is related to ROR audit index" when {
            "full index name is used" in {
              val rule = new RorInternalApiRule(Settings(
                access = Allow,
                configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
                indexAuditTemplate = Some(RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get)
              ))
              val requestContext = MockRequestContext.indices.copy(filteredIndices = Set(IndexName("test_2020-01-10")))
              val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

              inside(result) { case Fulfilled(_) => }
            }
            "index name with wildcard is used" in {
              val rule = new RorInternalApiRule(Settings(
                access = Allow,
                configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
                indexAuditTemplate = Some(RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get)
              ))
              val requestContext = MockRequestContext.indices.copy(filteredIndices = Set(IndexName("test*")))
              val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

              inside(result) { case Fulfilled(_) => }
            }
          }
          "uses index name different than ROR audit and config index" when {
            "full index name is used" in {
              val rule = new RorInternalApiRule(Settings(
                access = Allow,
                configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
                indexAuditTemplate = Some(RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get)
              ))
              val requestContext = MockRequestContext.indices.copy(filteredIndices = Set(IndexName("admin")))
              val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

              inside(result) { case Fulfilled(_) => }
            }
            "index name with wildcard is used" in {
              val rule = new RorInternalApiRule(Settings(
                access = Allow,
                configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
                indexAuditTemplate = Some(RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get)
              ))
              val requestContext = MockRequestContext.indices.copy(filteredIndices = Set(IndexName("admin*")))
              val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

              inside(result) { case Fulfilled(_) => }
            }
          }
        }
      }
    }
    "access is set to 'forbid'" should {
      "forbid request" when {
        "it's one of ROR actions request" when {
          "it's a ROR config request" in {
            val rule = new RorInternalApiRule(Settings(
              access = Forbid,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = None
            ))
            val requestContext = MockRequestContext.nonIndices.copy(action = Action.rorConfigAction)
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            result should be (Rejected(None))
          }
          "it's a legacy ROR config request" in {
            val rule = new RorInternalApiRule(Settings(
              access = Forbid,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = None
            ))
            val requestContext = MockRequestContext.nonIndices.copy(action = Action.rorOldConfigAction)
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            result should be (Rejected(None))
          }
          "it's a ROR audit event request" in {
            val rule = new RorInternalApiRule(Settings(
              access = Forbid,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = None
            ))
            val requestContext = MockRequestContext.nonIndices.copy(action = Action.rorAuditEventAction)
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            result should be (Rejected(None))
          }
        }
        "is related to ROR config index" when {
          "full index name is used" in {
            val rule = new RorInternalApiRule(Settings(
              access = Forbid,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = None
            ))
            val requestContext = MockRequestContext.indices.copy(filteredIndices = Set(IndexName(".readonlyrest")))
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            result should be (Rejected(None))
          }
          "index name with wildcard is used" in {
            val rule = new RorInternalApiRule(Settings(
              access = Forbid,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = None
            ))
            val requestContext = MockRequestContext.indices.copy(filteredIndices = Set(IndexName(".read*")))
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            result should be (Rejected(None))
          }
        }
        "is related to ROR audit index" when {
          "full index name is used" in {
            val rule = new RorInternalApiRule(Settings(
              access = Forbid,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = Some(RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get)
            ))
            val requestContext = MockRequestContext.indices.copy(filteredIndices = Set(IndexName("test_2020-01-10")))
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            result should be (Rejected(None))
          }
          "index name with wildcard is used" in {
            val rule = new RorInternalApiRule(Settings(
              access = Forbid,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = Some(RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get)
            ))
            val requestContext = MockRequestContext.indices.copy(filteredIndices = Set(IndexName("test*")))
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            result should be (Rejected(None))
          }
        }
      }
      "allow request" when {
        "uses index name different than ROR audit and config index" when {
          "full index name is used" in {
            val rule = new RorInternalApiRule(Settings(
              access = Forbid,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = Some(RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get)
            ))
            val requestContext = MockRequestContext.indices.copy(filteredIndices = Set(IndexName("admin")))
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            inside(result) { case Fulfilled(_) => }
          }
          "index name with wildcard is used" in {
            val rule = new RorInternalApiRule(Settings(
              access = Forbid,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = Some(RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get)
            ))
            val requestContext = MockRequestContext.indices.copy(filteredIndices = Set(IndexName("admin*")))
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            inside(result) { case Fulfilled(_) => }
          }
        }
      }
    }
  }
}

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
              val requestContext = MockRequestContext.indices.copy(
                filteredIndices = Set(IndexName(".readonlyrest")),
                isReadOnlyRequest = false
              )
              val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

              inside(result) { case Fulfilled(_) => }
            }
            "index name with wildcard is used" in {
              val rule = new RorInternalApiRule(Settings(
                access = Allow,
                configurationIndex = RorConfigurationIndex(IndexName(".ror")),
                indexAuditTemplate = None
              ))
              val requestContext = MockRequestContext.indices.copy(
                filteredIndices = Set(IndexName(".r*")),
                isReadOnlyRequest = false
              )
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
              val requestContext = MockRequestContext.indices.copy(
                filteredIndices = Set(IndexName("test_2020-01-10")),
                isReadOnlyRequest = false
              )
              val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

              inside(result) { case Fulfilled(_) => }
            }
            "index name with wildcard is used" in {
              val rule = new RorInternalApiRule(Settings(
                access = Allow,
                configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
                indexAuditTemplate = Some(RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get)
              ))
              val requestContext = MockRequestContext.indices.copy(
                filteredIndices = Set(IndexName("test*")),
                isReadOnlyRequest = false
              )
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
            val requestContext = MockRequestContext.indices.copy(
              filteredIndices = Set(IndexName(".readonlyrest")),
              isReadOnlyRequest = false
            )
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            result should be (Rejected(None))
          }
          "index name with wildcard is used" in {
            val rule = new RorInternalApiRule(Settings(
              access = Forbid,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = None
            ))
            val requestContext = MockRequestContext.indices.copy(
              filteredIndices = Set(IndexName(".read*")),
              isReadOnlyRequest = false
            )
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
            val requestContext = MockRequestContext.indices.copy(
              filteredIndices = Set(IndexName("test_2020-01-10")),
              isReadOnlyRequest = false
            )
            val result = rule.check(requestContext.initialBlockContext).runSyncUnsafe(1 second)

            result should be (Rejected(None))
          }
          "index name with wildcard is used" in {
            val rule = new RorInternalApiRule(Settings(
              access = Forbid,
              configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
              indexAuditTemplate = Some(RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get)
            ))
            val requestContext =  MockRequestContext.indices.copy(
              filteredIndices = Set(IndexName("test*")),
              isReadOnlyRequest = false
            )
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

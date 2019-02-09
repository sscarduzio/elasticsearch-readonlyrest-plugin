package tech.beshu.ror.unit.acl.blocks.rules


import com.softwaremill.sttp.Uri
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.TestsUtils.BlockContextAssertion
import tech.beshu.ror.acl.blocks.rules.KibanaAccessRule
import tech.beshu.ror.Constants
import tech.beshu.ror.acl.aDomain.Header.Name
import tech.beshu.ror.acl.aDomain.KibanaAccess.{RO, ROStrict, RW}
import tech.beshu.ror.acl.aDomain._
import tech.beshu.ror.acl.headerValues._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.{BlockContext, Const, RequestContextInitiatedBlockContext}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

class KibanaAccessRuleTests extends WordSpec with Inside with BlockContextAssertion[KibanaAccessRule.Settings] {

  "A KibanaAccessRule" when {
    "RO action is passed" in {
      Constants.RO_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(ROStrict), action)()
        assertMatchRule(settingsOf(RO), action)()
        assertMatchRule(settingsOf(RW), action)()
      }
    }
    "CLUSTER action is passed" in {
      Constants.CLUSTER_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(RO), action)()
        assertMatchRule(settingsOf(RW), action)()
      }
    }
    "RW action is passed" in {
      Constants.RW_ACTIONS.asScala.map(str => Action(str.replace("*", "_")))
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict), action, Set(IndexName(".kibana")))
          assertNotMatchRule(settingsOf(RO), action, Set(IndexName(".kibana")))
          assertMatchRule(settingsOf(RW), action, Set(IndexName(".kibana"))) {
            assertBlockContext(
              responseHeaders = Set(Header(Name.kibanaAccess, RW: KibanaAccess)),
              kibanaIndex = Some(IndexName(".kibana"))
            )
          }
        }
    }
    "RO action is passed with other indices" in {
      Constants.RO_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(ROStrict), action, Set(IndexName("xxx")))()
        assertMatchRule(settingsOf(RO), action, Set(IndexName("xxx")))()
        assertMatchRule(settingsOf(RW), action, Set(IndexName("xxx")))()
      }
    }
    "RW action is passed with other indices" in {
      Constants.RW_ACTIONS.asScala.map(Action.apply)
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict), action, Set(IndexName("xxx")))
          assertNotMatchRule(settingsOf(RO), action, Set(IndexName("xxx")))
          assertNotMatchRule(settingsOf(RW), action, Set(IndexName("xxx")))
        }
    }
    "RO action is passed with mixed indices" in {
      Constants.RO_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(ROStrict), action, Set(IndexName("xxx"), IndexName(".kibana")))()
        assertMatchRule(settingsOf(RO), action, Set(IndexName("xxx"), IndexName(".kibana")))()
        assertMatchRule(settingsOf(RW), action, Set(IndexName("xxx"), IndexName(".kibana")))()
      }
    }
    "RW action is passed with mixed indices" in {
      Constants.RW_ACTIONS.asScala.map(Action.apply)
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict), action, Set(IndexName("xxx"), IndexName(".kibana")))
          assertNotMatchRule(settingsOf(RO), action, Set(IndexName("xxx"), IndexName(".kibana")))
          assertNotMatchRule(settingsOf(RW), action, Set(IndexName("xxx"), IndexName(".kibana")))
        }
    }
    "RW action is passed with custom kibana index" in {
      Constants.RW_ACTIONS.asScala.map(Action.apply)
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict, IndexName(".custom_kibana")), action, Set(IndexName(".custom_kibana")))
          assertNotMatchRule(settingsOf(RO, IndexName(".custom_kibana")), action, Set(IndexName(".custom_kibana")))
          assertMatchRule(settingsOf(RW, IndexName(".custom_kibana")), action, Set(IndexName(".custom_kibana"))) {
            assertBlockContext(
              responseHeaders = Set(Header(Name.kibanaAccess, RW: KibanaAccess)),
              kibanaIndex = Some(IndexName(".custom_kibana"))
            )
          }
        }
    }
    "non strict operations (1)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana"),
        action = Action("indices:data/write/index"),
        uri = Uri("localhost", 8080, ".custom_kibana" :: "index-pattern" :: "job" :: Nil)
      )
    }
    "non strict operations (2)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana"),
        action = Action("indices:data/write/delete"),
        uri = Uri("localhost", 8080, ".custom_kibana" :: "index-pattern" :: "nilb-auh-filebeat-*" :: Nil)
      )
    }
    "non strict operations (3)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana"),
        action = Action("indices:admin/template/put"),
        uri = Uri("localhost", 8080, "_template" :: "kibana_index_template%3A.kibana" :: Nil)
      )
    }
    "non strict operations (4)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana"),
        action = Action("indices:data/write/update"),
        uri = Uri("localhost", 8080, ".custom_kibana" :: "doc" :: "index-pattern%3A895e56e0-d873-11e8-bd16-3dcc5288c87b " :: "_update?" :: Nil)
      )
    }
  }

  private def testNonStrictOperations(customKibanaIndex: IndexName, action: Action, uri: Uri): Unit = {
    assertNotMatchRule(settingsOf(ROStrict, customKibanaIndex), action, Set(customKibanaIndex), Some(uri))
    assertMatchRule(settingsOf(RO, customKibanaIndex), action, Set(customKibanaIndex), Some(uri)){
      assertBlockContext(
        responseHeaders = Set(Header(Name.kibanaAccess, RO: KibanaAccess)),
        kibanaIndex = Some(customKibanaIndex)
      )
    }
    assertMatchRule(settingsOf(RW, customKibanaIndex), action, Set(customKibanaIndex), Some(uri)) {
      assertBlockContext(
        responseHeaders = Set(Header(Name.kibanaAccess, RW: KibanaAccess)),
        kibanaIndex = Some(customKibanaIndex)
      )
    }
  }

  private def assertMatchRule(settings: KibanaAccessRule.Settings, action: Action, indices: Set[IndexName] = Set.empty, url: Option[Uri] = None)
                             (blockContextAssertion: BlockContext => Unit = defaultOutputBlockContextAssertion(settings)) =
    assertRule(settings, action, indices, url, Some(blockContextAssertion))

  private def assertNotMatchRule(settings: KibanaAccessRule.Settings, action: Action, indices: Set[IndexName] = Set.empty, url: Option[Uri] = None) =
    assertRule(settings, action, indices, url, blockContextAssertion = None)

  private def assertRule(settings: KibanaAccessRule.Settings,
                         action: Action,
                         indices: Set[IndexName],
                         url: Option[Uri] = None,
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = new KibanaAccessRule(settings)
    val requestContext = MockRequestContext(
      action = action,
      indices = indices,
      uri = url.getOrElse(Uri("localhost"))
    )
    val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)
    val result = rule.check(requestContext, blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Fulfilled(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Rejected)
    }
  }

  private def settingsOf(access: KibanaAccess, kibanaIndex: IndexName = IndexName(".kibana")) = {
    KibanaAccessRule.Settings(access, Const(kibanaIndex), kibanaMetadataEnabled = true)
  }

  private def defaultOutputBlockContextAssertion(settings: KibanaAccessRule.Settings): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(responseHeaders = Set(Header(Name.kibanaAccess, settings.access)))(blockContext)
    }

}

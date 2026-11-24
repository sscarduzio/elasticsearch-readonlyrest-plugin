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
package tech.beshu.ror.integration

import cats.data.NonEmptyList
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditOutputConfig.*
import tech.beshu.ror.accesscontrol.audit.AuditingTool.{AuditOutputs, AuditSetup, AuditingConfig}
import tech.beshu.ror.accesscontrol.audit.{AuditSerializer, AuditingTool, EsAuditCapabilities, LoggingContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.logging.AccessControlListLoggingDecorator
import tech.beshu.ror.audit.instances.BlockVerbosityAwareAuditLogSerializer
import tech.beshu.ror.es.services.IndexBasedAuditOutputService
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockRequestContext}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.TestsUtils.{defaultTestEsNodeSettings, testRequestId}

import java.time.{Clock, Instant, ZoneId}
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, Promise}
import scala.language.postfixOps

class AuditBlocksHistoryTests extends AnyWordSpec with BaseYamlLoadedAccessControlTest {

  private val blockNames = List("Block 1", "Block 2", "Block 3", "Block 4", "Block 5", "Block 6", "Block 7", "Block 8")

  override protected def settingsYaml: String =
    """
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "Block 1"
      |    auth_key: user1:pass
      |
      |  - name: "Block 2"
      |    auth_key: user2:pass
      |
      |  - name: "Block 3"
      |    auth_key: user3:pass
      |
      |  - name: "Block 4"
      |    auth_key: user4:pass
      |
      |  - name: "Block 5"
      |    auth_key: user5:pass
      |
      |  - name: "Block 6"
      |    auth_key: user6:pass
      |
      |  - name: "Block 7"
      |    auth_key: user7:pass
      |
      |  - name: "Block 8"
      |    auth_key: user8:pass
    """.stripMargin

  private implicit val clock: Clock = Clock.fixed(Instant.parse("2020-01-01T00:00:00.00Z"), ZoneId.of("UTC"))

  "The blocks_history field of an audit event" should {
    "list the blocks in the order of the evaluation" when {
      "the ACL has more than 4 blocks" in {
        val auditOutputService = new MockedIndexAuditOutputService()
        val acl = auditedAcl(auditOutputService)

        acl.handleRegularRequest(MockRequestContext.indices).runSyncUnsafe()

        val (_, jsonString) = Await.result(auditOutputService.result, 5 seconds)
        ujson.read(jsonString)("blocks_history").arr.map(_("block_name").str).toList should be(blockNames)
      }
    }
  }

  private def auditedAcl(indexBasedAuditOutputService: IndexBasedAuditOutputService) = {
    implicit val loggingContext: LoggingContext = LoggingContext(Set.empty)
    val settings = AuditOutputs.Configured(
      NonEmptyList.one[AuditSetup.OutputSupportedByAllEsVersions](
        EsIndexBased(
          AuditOutputName.random(),
          EsIndexBased.Config(
            AuditSerializer.Delegating(new BlockVerbosityAwareAuditLogSerializer),
            RorAuditIndexTemplate.default,
            AuditCluster.LocalAuditCluster
          )
        )
      )
    )
    val auditingTool = AuditingTool
      .create(
        setup = new AuditSetup.SupportedByAllEsVersions(
          capability = new EsAuditCapabilities.IndexOnly((_: AuditCluster) => indexBasedAuditOutputService),
          config = AuditingConfig(settings, defaultAclLog = false, defaultTestEsNodeSettings),
        ),
        httpClientsFactory = MockHttpClientsFactory,
      )
      .runSyncUnsafe()
      .toOption
      .get
    new AccessControlListLoggingDecorator(acl, auditingTool)
  }

  private class MockedIndexAuditOutputService extends IndexBasedAuditOutputService {
    private val submittedIndexAndJson: Promise[(IndexName.Full, String)] = Promise()

    override def submit(
        indexName: IndexName.Full,
        documentId: String,
        jsonRecord: String,
        pipeline: Option[AuditIngestPipeline]
    )(
        implicit requestId: RequestId
    ): Unit = {
      submittedIndexAndJson.trySuccess(indexName, jsonRecord)
    }

    override def close(): Unit = ()

    def result: Future[(IndexName.Full, String)] = submittedIndexAndJson.future
  }

}

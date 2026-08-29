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
package tech.beshu.ror.unit.acl.logging

import better.files.*
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Resource
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.json.JSONObject
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import squants.information.Megabytes
import tech.beshu.ror.accesscontrol.History
import tech.beshu.ror.accesscontrol.audit.AuditSerializer
import tech.beshu.ror.accesscontrol.audit.AuditingTool
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditOutputConfig.*
import tech.beshu.ror.accesscontrol.audit.AuditingTool.{AuditOutputConfig, AuditOutputs, AuditSetup, AuditingConfig}
import tech.beshu.ror.accesscontrol.audit.EsAuditCapabilities
import tech.beshu.ror.accesscontrol.audit.output.{
  AuditDataStreamCreator,
  AuditOutput,
  DataStreamBasedAuditOutputServiceCreator,
  IndexBasedAuditOutputServiceCreator
}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.Policy
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{
  GeneralIndexRequestBlockContext,
  UserMetadataRequestBlockContext
}
import tech.beshu.ror.accesscontrol.blocks.metadata.{BlockMetadata, UserMetadata}
import tech.beshu.ror.accesscontrol.blocks.rules.http.MethodsRule
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.logging.ResponseContext.*
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}
import tech.beshu.ror.es.services.{DataStreamBasedAuditOutputService, DataStreamService, IndexBasedAuditOutputService}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RefinedUtils.positiveInt
import tech.beshu.ror.utils.TestsUtils.*

import java.nio.file.attribute.PosixFilePermission
import java.time.*
import java.util.UUID
import scala.annotation.nowarn

class AuditingToolTests extends AnyWordSpec with MockFactory with BeforeAndAfterAll {

  import tech.beshu.ror.utils.TestsUtils.loggingContext

  private val auditLogFile = File("/tmp/ror/audit_logs/test_audit.log")

  "Auditing tool" when {
    "used with DefaultAuditLogSerializer" when {
      "es index output is used" should {
        "not submit any audit entry" when {
          "request was allowed and verbosity level was ERROR" in {
            @nowarn("cat=deprecation")
            val auditingTool = AuditingTool
              .create(
                auditSetupForAnyOutput(
                  config = AuditingConfig(
                    configuredAuditOutputs(new DefaultAuditLogSerializer),
                    defaultAclLog = true,
                    defaultTestEsNodeSettings
                  ),
                  indexCreator = (_: AuditCluster) => mock[IndexBasedAuditOutputService],
                  dataStreamCreator = (_: AuditCluster) => mockedDataStreamBasedAuditOutputService
                )
              )
              .runSyncUnsafe()
              .toOption
              .get
            auditingTool
              .audit(createAllowedResponseContext(Policy.Allow, auditingTool.outputs, logAllowedEvents = false))
              .runSyncUnsafe()
          }
          "custom serializer throws exception" in {
            val auditingTool = AuditingTool
              .create(
                auditSetupForAnyOutput(
                  config = AuditingConfig(
                    configuredAuditOutputs(throwingAuditLogSerializer),
                    defaultAclLog = true,
                    defaultTestEsNodeSettings
                  ),
                  indexCreator = (_: AuditCluster) => mock[IndexBasedAuditOutputService],
                  dataStreamCreator = (_: AuditCluster) => mockedDataStreamBasedAuditOutputService
                )
              )
              .runSyncUnsafe()
              .toOption
              .get
            an[IllegalArgumentException] should be thrownBy {
              auditingTool.audit(createAllowedResponseContext(Policy.Allow, auditingTool.outputs)).runSyncUnsafe()
            }
          }
        }
        "submit audit entry" when {
          "request was allowed and verbosity level was INFO" in {
            val requestId = RequestId("mock-1")
            val indexAuditOutput = mock[IndexBasedAuditOutputService]
            (indexAuditOutput
              .submit(_: IndexName.Full, _: String, _: String)(_: RequestId))
              .expects(fullIndexName("test_2018-12-31"), "mock-1", *, requestId)
              .returning(())
            val dataStreamAuditOutput = mockedDataStreamBasedAuditOutputService
            (dataStreamAuditOutput
              .submit(_: DataStreamName.Full, _: String, _: String)(_: RequestId))
              .expects(fullDataStreamName("test_ds"), "mock-1", *, RequestId("mock-1"))
              .returning(())
            @nowarn("cat=deprecation")
            val auditingTool = AuditingTool
              .create(
                auditSetupForAnyOutput(
                  config = AuditingConfig(
                    configuredAuditOutputs(new DefaultAuditLogSerializer),
                    defaultAclLog = true,
                    defaultTestEsNodeSettings
                  ),
                  indexCreator = (_: AuditCluster) => indexAuditOutput,
                  dataStreamCreator = (_: AuditCluster) => dataStreamAuditOutput
                )
              )
              .runSyncUnsafe()
              .toOption
              .get
            auditingTool.audit(createAllowedResponseContext(Policy.Allow, auditingTool.outputs)).runSyncUnsafe()
          }
          "request was matched by forbidden rule" in {
            val requestId = RequestId("mock-1")
            val indexAuditOutput = mock[IndexBasedAuditOutputService]
            (indexAuditOutput
              .submit(_: IndexName.Full, _: String, _: String)(_: RequestId))
              .expects(fullIndexName("test_2018-12-31"), "mock-1", *, requestId)
              .returning(())
            val dataStreamAuditOutput = mockedDataStreamBasedAuditOutputService
            (dataStreamAuditOutput
              .submit(_: DataStreamName.Full, _: String, _: String)(_: RequestId))
              .expects(fullDataStreamName("test_ds"), "mock-1", *, requestId)
              .returning(())
            @nowarn("cat=deprecation")
            val auditingTool = AuditingTool
              .create(
                auditSetupForAnyOutput(
                  config = AuditingConfig(
                    configuredAuditOutputs(new DefaultAuditLogSerializer),
                    defaultAclLog = true,
                    defaultTestEsNodeSettings
                  ),
                  indexCreator = (_: AuditCluster) => indexAuditOutput,
                  dataStreamCreator = (_: AuditCluster) => dataStreamAuditOutput
                )
              )
              .runSyncUnsafe()
              .toOption
              .get

            val requestContext = MockRequestContext.indices.copy(
              timestamp = someday.toInstant,
              id = RequestContext.Id.fromString("mock-1")
            )
            val responseContext = ForbiddenBy(
              requestContext = requestContext,
              blockContext = GeneralIndexRequestBlockContext(
                block = new Block(
                  Block.Name("mock-block"),
                  Block.Policy.Forbid(),
                  NonEmptyList.one(new MethodsRule(MethodsRule.Settings(NonEmptySet.one(Method.GET)))),
                  Block.Audit.Enabled(),
                ).withResolvedAuditOutputs(auditingTool.outputs),
                requestContext = requestContext,
                blockMetadata = BlockMetadata.empty,
                responseHeaders = Set.empty,
                responseTransformations = List.empty,
                filteredIndices = Set.empty,
                allAllowedIndices = Set.empty,
                allAllowedClusters = Set.empty
              ),
              history = History.empty
            )

            auditingTool.audit(responseContext).runSyncUnsafe()
          }
          "request was forbidden" in {
            val requestId = RequestId("mock-1")
            val indexAuditOutput = mock[IndexBasedAuditOutputService]
            (indexAuditOutput
              .submit(_: IndexName.Full, _: String, _: String)(_: RequestId))
              .expects(fullIndexName("test_2018-12-31"), "mock-1", *, requestId)
              .returning(())
            val dataStreamAuditOutput = mockedDataStreamBasedAuditOutputService
            (dataStreamAuditOutput
              .submit(_: DataStreamName.Full, _: String, _: String)(_: RequestId))
              .expects(fullDataStreamName("test_ds"), "mock-1", *, requestId)
              .returning(())
            @nowarn("cat=deprecation")
            val auditingTool = AuditingTool
              .create(
                auditSetupForAnyOutput(
                  config = AuditingConfig(
                    configuredAuditOutputs(new DefaultAuditLogSerializer),
                    defaultAclLog = true,
                    defaultTestEsNodeSettings
                  ),
                  indexCreator = (_: AuditCluster) => indexAuditOutput,
                  dataStreamCreator = (_: AuditCluster) => dataStreamAuditOutput
                )
              )
              .runSyncUnsafe()
              .toOption
              .get

            val requestContext = MockRequestContext.indices.copy(
              timestamp = someday.toInstant,
              id = RequestContext.Id.fromString("mock-1")
            )
            val responseContext = Forbidden(requestContext, History.empty)

            auditingTool.audit(responseContext).runSyncUnsafe()
          }
          "request was finished with error" in {
            val requestId = RequestId("mock-1")
            val indexAuditOutput = mock[IndexBasedAuditOutputService]
            (indexAuditOutput
              .submit(_: IndexName.Full, _: String, _: String)(_: RequestId))
              .expects(fullIndexName("test_2018-12-31"), "mock-1", *, requestId)
              .returning(())
            val dataStreamAuditOutput = mockedDataStreamBasedAuditOutputService
            (dataStreamAuditOutput
              .submit(_: DataStreamName.Full, _: String, _: String)(_: RequestId))
              .expects(fullDataStreamName("test_ds"), "mock-1", *, requestId)
              .returning(())
            @nowarn("cat=deprecation")
            val auditingTool = AuditingTool
              .create(
                auditSetupForAnyOutput(
                  config = AuditingConfig(
                    configuredAuditOutputs(new DefaultAuditLogSerializer),
                    defaultAclLog = true,
                    defaultTestEsNodeSettings
                  ),
                  indexCreator = (_: AuditCluster) => indexAuditOutput,
                  dataStreamCreator = (_: AuditCluster) => dataStreamAuditOutput
                )
              )
              .runSyncUnsafe()
              .toOption
              .get

            val requestContext = MockRequestContext.indices.copy(
              timestamp = someday.toInstant,
              id = RequestContext.Id.fromString("mock-1")
            )
            val responseContext = Errored(requestContext, new Exception("error"))

            auditingTool.audit(responseContext).runSyncUnsafe()
          }
        }
      }
      "log output is used" should {
        "saved audit log to file defined in log4j settings" in {
          @nowarn("cat=deprecation")
          val auditingTool = AuditingTool
            .create(
              auditSetupForAnyOutput(
                config = AuditingConfig(
                  outputs = AuditOutputs.Configured(
                    NonEmptyList.of(
                      LogBased(
                        AuditOutputName.random(),
                        LogBased.Config(
                          AuditSerializer.Delegating(new DefaultAuditLogSerializer),
                          RorAuditLoggerName.default
                        )
                      )
                    )
                  ),
                  defaultAclLog = true,
                  esNodeSettings = defaultTestEsNodeSettings,
                ),
                indexCreator = (_: AuditCluster) => mock[IndexBasedAuditOutputService],
                dataStreamCreator = (_: AuditCluster) => mock[DataStreamBasedAuditOutputService]
              )
            )
            .runSyncUnsafe()
            .toOption
            .get

          val requestContextId = RequestContext.Id.fromString(UUID.randomUUID().toString)
          val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = requestContextId)
          val responseContext = Errored(requestContext, new Exception("error"))

          auditLogFile.overwrite("")

          auditingTool.audit(responseContext).runSyncUnsafe()
          val logFileContent = auditLogFile.contentAsString

          logFileContent should include(requestContextId.value)
        }
        "write audit log exclusively to the configured file_path via its RollingFileAppender" in {
          // Use a logger name that has NO pre-configured Log4j appenders, so the
          // RollingFileAppender we attach programmatically is the only possible writer.
          val isolatedLoggerName = RorAuditLoggerName(nes("ror-audit-isolated-file-appender-test"))
          val filePathAuditLog = File("/tmp/ror/audit_logs/test_isolated_file_path_audit.log")
          filePathAuditLog.parent.createDirectories()
          filePathAuditLog.overwrite("")

          @nowarn("cat=deprecation")
          val auditingTool = AuditingTool
            .create(
              auditSetupForAnyOutput(
                config = AuditingConfig(
                  outputs = AuditOutputs.Configured(
                    NonEmptyList.of(
                      RollingFileBased(
                        AuditOutputName.random(),
                        RollingFileBased.Config(
                          serializer = AuditSerializer.Delegating(new DefaultAuditLogSerializer),
                          loggerName = isolatedLoggerName,
                          fileAppender = RollingFileBased.FileAppender(
                            filePath = filePathAuditLog.path,
                            maxFileSize = Megabytes(100),
                            maxFiles = positiveInt(7)
                          )
                        )
                      )
                    )
                  ),
                  defaultAclLog = true,
                  esNodeSettings = defaultTestEsNodeSettings,
                ),
                indexCreator = (_: AuditCluster) => mock[IndexBasedAuditOutputService],
                dataStreamCreator = (_: AuditCluster) => mock[DataStreamBasedAuditOutputService]
              )
            )
            .runSyncUnsafe()
            .toOption
            .get

          filePathAuditLog.contentAsString shouldBe empty

          val requestContextId = RequestContext.Id.fromString(UUID.randomUUID().toString)
          val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = requestContextId)
          val responseContext = Errored(requestContext, new Exception("error"))

          auditingTool.audit(responseContext).runSyncUnsafe()
          auditingTool.close().runSyncUnsafe()

          filePathAuditLog.contentAsString should include(requestContextId.value)
        }
        "should write to custom log file when defaultAclLog is also enabled" in {
          val customLogFile = File("/tmp/ror/audit_logs/test_both_outputs_audit.log")
          customLogFile.parent.createDirectories()
          customLogFile.overwrite("")

          val customLoggerName = RorAuditLoggerName(nes("ror-audit-both-outputs-test"))

          @nowarn("cat=deprecation")
          val auditingTool = AuditingTool
            .create(
              auditSetupForAnyOutput(
                config = AuditingConfig(
                  outputs = AuditOutputs.Configured(
                    NonEmptyList.of(
                      RollingFileBased(
                        AuditOutputName.random(),
                        RollingFileBased.Config(
                          serializer = AuditSerializer.Delegating(new DefaultAuditLogSerializer),
                          loggerName = customLoggerName,
                          fileAppender = RollingFileBased.FileAppender(
                            filePath = customLogFile.path,
                            maxFileSize = Megabytes(100),
                            maxFiles = positiveInt(7)
                          )
                        )
                      )
                    )
                  ),
                  defaultAclLog = true,
                  esNodeSettings = defaultTestEsNodeSettings,
                ),
                indexCreator = (_: AuditCluster) => mock[IndexBasedAuditOutputService],
                dataStreamCreator = (_: AuditCluster) => mock[DataStreamBasedAuditOutputService]
              )
            )
            .runSyncUnsafe()
            .toOption
            .get

          val requestContextId = RequestContext.Id.fromString(UUID.randomUUID().toString)
          val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = requestContextId)
          val responseContext = Errored(requestContext, new Exception("error"))

          auditingTool.audit(responseContext).runSyncUnsafe()
          auditingTool.close().runSyncUnsafe()

          customLogFile.contentAsString should include(requestContextId.value)
        }
      }
    }
    "rolling file output is used" should {
      "return a creation error" when {
        "the parent directory does not exist" in {
          // Log4j creates missing directories via Files.createDirectories, so to reliably
          // prevent creation we need the grandparent to be non-writable.
          val tempDir = File.newTemporaryDirectory("ror-audit-test-")
          try {
            tempDir.setPermissions(
              scala.collection.immutable.Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)
            )
            assume(!tempDir.isWritable, "Skipping: running as root bypasses permission checks")
            val subDir = tempDir / "subdir"
            val logPath = (subDir / "audit.log").path

            val result = AuditingTool
              .create(
                auditSetupForAnyOutput(
                  config = AuditingConfig(
                    rollingFileOutputSettings(logPath),
                    defaultAclLog = true,
                    defaultTestEsNodeSettings
                  ),
                  indexCreator = (_: AuditCluster) => mock[IndexBasedAuditOutputService],
                  dataStreamCreator = (_: AuditCluster) => mock[DataStreamBasedAuditOutputService]
                )
              )
              .runSyncUnsafe()

            result match {
              case Left(errors) =>
                errors.head.message should include("does not exist")
                errors.head.message should include(subDir.path.toString)
              case Right(_) =>
                fail("Expected creation error but got success")
            }
          } finally {
            tempDir.setPermissions(
              scala.collection.immutable
                .Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            )
            tempDir.delete(swallowIOExceptions = true)
          }
        }

        "the parent directory has no write permission" in {
          val tempDir = File.newTemporaryDirectory("ror-audit-test-")
          try {
            tempDir.setPermissions(
              scala.collection.immutable.Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)
            )
            assume(!tempDir.isWritable, "Skipping: running as root bypasses permission checks")
            val logPath = (tempDir / "audit.log").path

            val result = AuditingTool
              .create(
                auditSetupForAnyOutput(
                  config = AuditingConfig(
                    rollingFileOutputSettings(logPath),
                    defaultAclLog = true,
                    defaultTestEsNodeSettings
                  ),
                  indexCreator = (_: AuditCluster) => mock[IndexBasedAuditOutputService],
                  dataStreamCreator = (_: AuditCluster) => mock[DataStreamBasedAuditOutputService]
                )
              )
              .runSyncUnsafe()

            result match {
              case Left(errors) =>
                errors.head.message should include("no write permission")
                errors.head.message should include(tempDir.path.toString)
              case Right(_) =>
                fail("Expected creation error but got success")
            }
          } finally {
            tempDir.setPermissions(
              scala.collection.immutable
                .Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            )
            tempDir.delete(swallowIOExceptions = true)
          }
        }

        "the log file exists but is not writable" in {
          val tempDir = File.newTemporaryDirectory("ror-audit-test-")
          try {
            val logFile = tempDir / "audit.log"
            logFile.touch()
            logFile.setPermissions(scala.collection.immutable.Set(PosixFilePermission.OWNER_READ))
            assume(!logFile.isWritable, "Skipping: running as root bypasses permission checks")
            val logPath = logFile.path

            val result = AuditingTool
              .create(
                auditSetupForAnyOutput(
                  config = AuditingConfig(
                    rollingFileOutputSettings(logPath),
                    defaultAclLog = true,
                    defaultTestEsNodeSettings
                  ),
                  indexCreator = (_: AuditCluster) => mock[IndexBasedAuditOutputService],
                  dataStreamCreator = (_: AuditCluster) => mock[DataStreamBasedAuditOutputService]
                )
              )
              .runSyncUnsafe()

            result match {
              case Left(errors) =>
                errors.head.message should include("no write permission")
                errors.head.message should include(logFile.path.toString)
              case Right(_) =>
                fail("Expected creation error but got success")
            }
          } finally {
            tempDir.delete(swallowIOExceptions = true)
          }
        }
      }
    }

    "multi-group user matched by the same block" should {
      "submit audit event to each output exactly once, not once per matched group" in {
        // A user can belong to multiple groups all granted by the same block.
        // The audit event describes the request, not the group — so each output
        // should receive it once regardless of how many groups matched.
        val requestId = RequestId("mock-withgroups-dedup")
        val indexAuditOutput = mock[IndexBasedAuditOutputService]
        (indexAuditOutput
          .submit(_: IndexName.Full, _: String, _: String)(_: RequestId))
          .expects(fullIndexName("test_2018-12-31"), "mock-withgroups-dedup", *, requestId)
          .returning(())
          .once()

        @nowarn("cat=deprecation")
        val auditingTool = AuditingTool
          .create(
            auditSetupForAnyOutput(
              config = AuditingConfig(
                AuditOutputs.Configured(
                  NonEmptyList.of(
                    EsIndexBased(
                      AuditOutputName.random(),
                      EsIndexBased.Config(
                        AuditSerializer.Delegating(new DefaultAuditLogSerializer),
                        RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").toOption.get,
                        AuditCluster.LocalAuditCluster
                      )
                    )
                  )
                ),
                defaultAclLog = false,
                defaultTestEsNodeSettings
              ),
              indexCreator = (_: AuditCluster) => indexAuditOutput,
              dataStreamCreator = (_: AuditCluster) => mock[DataStreamBasedAuditOutputService]
            )
          )
          .runSyncUnsafe()
          .toOption
          .get

        val metaRequestContext = MockRequestContext.metadata.copy(
          timestamp = someday.toInstant,
          id = RequestContext.Id.fromString("mock-withgroups-dedup")
        )

        val block = new Block(
          Block.Name("mock-block"),
          Block.Policy.Allow,
          NonEmptyList.one(new MethodsRule(MethodsRule.Settings(NonEmptySet.one(Method.GET)))),
          Block.Audit.Enabled()
        ).withResolvedAuditOutputs(auditingTool.outputs)

        val blockContext = UserMetadataRequestBlockContext(
          block = block,
          requestContext = metaRequestContext,
          blockMetadata = BlockMetadata.empty,
          responseHeaders = Set.empty,
          responseTransformations = List.empty
        )

        val metadataOrigin = UserMetadata.MetadataOrigin(blockContext)
        val makeGroupMetadata = (groupStr: String) =>
          UserMetadata.WithGroups.GroupMetadata(
            group = group(groupStr),
            loggedUser = LoggedUser.DirectlyLoggedUser(User.Id(nes("testuser"))),
            userOrigin = None,
            kibanaPolicy = None,
            metadataOrigin = metadataOrigin
          )

        val responseContext = Allowed(
          requestContext = metaRequestContext,
          userMetadata =
            UserMetadata.WithGroups(NonEmptyList.of(makeGroupMetadata("group1"), makeGroupMetadata("group2"))),
          history = History.empty
        )

        auditingTool.audit(responseContext).runSyncUnsafe()
      }
    }

    "index-only audit settings" should {
      "submit audit entry to index output" in {
        val requestId = RequestId("mock-1")
        val indexAuditOutput = mock[IndexBasedAuditOutputService]
        (indexAuditOutput
          .submit(_: IndexName.Full, _: String, _: String)(_: RequestId))
          .expects(fullIndexName("test_2018-12-31"), "mock-1", *, requestId)
          .returning(())
        @nowarn("cat=deprecation")
        val auditingTool = AuditingTool
          .create(
            auditSetupForAllEsVersions(
              config = auditingConfigSupportedByAllEsVersions(new DefaultAuditLogSerializer),
              creator = (_: AuditCluster) => indexAuditOutput
            )
          )
          .runSyncUnsafe()
          .toOption
          .get
        auditingTool.audit(createAllowedResponseContext(Policy.Allow, auditingTool.outputs)).runSyncUnsafe()
      }
    }
    "output configuration" should {
      "create a tool with no configured outputs when disabled" in {
        val creationResult = AuditingTool
          .create(
            auditSetupForAllEsVersions(
              config = AuditingConfig(
                AuditOutputs.Disabled,
                defaultAclLog = false,
                defaultTestEsNodeSettings
              ),
              creator = (_: AuditCluster) => mock[IndexBasedAuditOutputService]
            )
          )
          .runSyncUnsafe()

        creationResult.map(_.outputs.isEmpty) should be(Right(true))
      }
      "create the default index output when defaults are requested" in {
        val creationResult = AuditingTool
          .create(
            auditSetupForAllEsVersions(
              config = AuditingConfig(
                AuditOutputs.Defaults,
                defaultAclLog = false,
                defaultTestEsNodeSettings
              ),
              creator = (_: AuditCluster) => mock[IndexBasedAuditOutputService]
            )
          )
          .runSyncUnsafe()

        creationResult.map(_.outputs.size) should be(Right(1))
      }
    }
    "no enabled outputs in settings" should {
      "create a tool with no active outputs" in {
        val creationResult = AuditingTool
          .create(
            auditSetupForAnyOutput(
              config = AuditingConfig(
                AuditOutputs.Disabled,
                defaultAclLog = true,
                defaultTestEsNodeSettings,
              ),
              indexCreator = (_: AuditCluster) => mock[IndexBasedAuditOutputService],
              dataStreamCreator = (_: AuditCluster) => mock[DataStreamBasedAuditOutputService]
            )
          )
          .runSyncUnsafe()
        creationResult.isRight should be(true)
      }
    }
  }

  private def auditSetupForAnyOutput(
      config: AuditingConfig[AuditOutputConfig],
      indexCreator: IndexBasedAuditOutputServiceCreator,
      dataStreamCreator: DataStreamBasedAuditOutputServiceCreator
  ): AuditSetup =
    new AuditSetup.AnyOutput(
      new EsAuditCapabilities.IndexOrDataStream(indexCreator, dataStreamCreator),
      config
    )

  private def auditSetupForAllEsVersions(
      config: AuditingConfig[AuditSetup.OutputSupportedByAllEsVersions],
      creator: IndexBasedAuditOutputServiceCreator
  ): AuditSetup =
    new AuditSetup.SupportedByAllEsVersions(new EsAuditCapabilities.IndexOnly(creator), config)

  private def auditingConfigSupportedByAllEsVersions(
      serializer: AuditLogSerializer
  ): AuditingConfig[AuditSetup.OutputSupportedByAllEsVersions] = AuditingConfig(
    outputs = AuditOutputs.Configured(
      NonEmptyList.of(
        EsIndexBased(
          AuditOutputName.random(),
          EsIndexBased.Config(
            AuditSerializer.Delegating(serializer),
            RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").toOption.get,
            AuditCluster.LocalAuditCluster
          )
        )
      )
    ),
    defaultAclLog = false,
    esNodeSettings = defaultTestEsNodeSettings
  )

  private def configuredAuditOutputs(serializer: AuditLogSerializer) = AuditOutputs.Configured(
    outputs = NonEmptyList.of(
      EsIndexBased(
        AuditOutputName.random(),
        EsIndexBased.Config(
          AuditSerializer.Delegating(serializer),
          RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").toOption.get,
          AuditCluster.LocalAuditCluster
        )
      ),
      EsDataStreamBased(
        AuditOutputName.random(),
        EsDataStreamBased.Config(
          AuditSerializer.Delegating(serializer),
          RorAuditDataStream.from("test_ds").toOption.get,
          AuditCluster.LocalAuditCluster
        )
      )
    )
  )

  private lazy val someday = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))

  private def createAllowedResponseContext(
      policy: Block.Policy,
      allOutputs: List[AuditOutput],
      logAllowedEvents: Boolean = true
  ) = {
    val requestContext =
      MockRequestContext.indices.copy(timestamp = someday.toInstant, id = RequestContext.Id.fromString("mock-1"))
    AllowedBy(
      requestContext = requestContext,
      blockContext = GeneralIndexRequestBlockContext(
        block = new Block(
          name = Block.Name("mock-block"),
          policy = policy,
          rules = NonEmptyList.one(new MethodsRule(MethodsRule.Settings(NonEmptySet.one(Method.GET)))),
          audit = Block.Audit.Enabled(logAllowedEvents),
        ).withResolvedAuditOutputs(allOutputs),
        requestContext = requestContext,
        blockMetadata = BlockMetadata.empty,
        responseHeaders = Set.empty,
        responseTransformations = List.empty,
        filteredIndices = Set.empty,
        allAllowedIndices = Set.empty,
        allAllowedClusters = Set.empty
      ),
      history = History.empty
    )
  }

  private implicit val fixedClock: Clock = Clock.fixed(someday.toInstant, someday.getZone)

  @nowarn("cat=deprecation")
  private def rollingFileOutputSettings(filePath: java.nio.file.Path) = AuditOutputs.Configured(
    NonEmptyList.of(
      RollingFileBased(
        AuditOutputName.random(),
        RollingFileBased.Config(
          serializer = AuditSerializer.Delegating(new DefaultAuditLogSerializer),
          loggerName = RorAuditLoggerName(nes("ror-audit-error-test")),
          fileAppender = RollingFileBased.FileAppender(
            filePath = filePath,
            maxFileSize = Megabytes(100),
            maxFiles = positiveInt(7)
          )
        )
      )
    )
  )

  private lazy val throwingAuditLogSerializer = new AuditLogSerializer {
    override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
      throw new IllegalArgumentException("sth went wrong")
    }
  }

  private def mockedDataStreamBasedAuditOutputService: DataStreamBasedAuditOutputService = {
    val mockedDataStreamService = mock[DataStreamService]

    (mockedDataStreamService
      .checkDataStreamExists(_: DataStreamName.Full))
      .expects(fullDataStreamName(nes("test_ds")))
      .returning(Task.now(true))

    val mockedService = mock[DataStreamBasedAuditOutputService]
    (() => mockedService.dataStreamCreator)
      .expects()
      .returns(Resource.pure(AuditDataStreamCreator(mockedDataStreamService)))

    mockedService
  }

}

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
package tech.beshu.ror.unit.es

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.audit.sink.AuditDataStreamCreator
import tech.beshu.ror.accesscontrol.audit.sink.AuditDataStreamCreator.ErrorMessage
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, RorAuditDataStream, TemplateName}
import tech.beshu.ror.es.DataStreamService
import tech.beshu.ror.es.DataStreamService.CreationResult.*
import tech.beshu.ror.es.DataStreamService.{CreationResult, DataStreamSettings}

import scala.concurrent.duration.DurationInt


class DataStreamServiceTest
  extends AnyWordSpec
    with MockFactory {

  private val auditDs = RorAuditDataStream.default
  private val expectedLifecyclePolicyName = NonEmptyString.unsafeFrom(s"${auditDs.dataStream.value.value}-lifecycle-policy")
  private val expectedMappingsTemplateName = TemplateName(NonEmptyString.unsafeFrom(s"${auditDs.dataStream.value.value}-mappings"))
  private val expectedSettingsTemplateName = TemplateName(NonEmptyString.unsafeFrom(s"${auditDs.dataStream.value.value}-settings"))
  private val expectedIndexTemplateName = TemplateName(NonEmptyString.unsafeFrom(s"${auditDs.dataStream.value.value}-template"))

  private type MockFun = MockableDataStreamService => Unit

  private val doesExist = true
  private val doesNotExist = false
  private val checkedOnce = 1

  "A ReadonlyREST data stream service" when {
    "fully setup data stream called" should {
      "not attempt to create data stream when one exists" in {
        val service: DataStreamService = createMockedDataStreamService(
          List(
            _.mockCheckDataStreamExists(doesExist, checkedOnce)
          )
        )
        val auditDataStreamCreator = AuditDataStreamCreator.create(service)

        val result = auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
        result shouldBe Right(())
      }
      "attempt to create data stream with success" when {
        "all resources available immediately" in {
          val service: DataStreamService = createMockedDataStreamService(
            List(
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCheckLifecyclePolicyExists(doesExist, checkedOnce),
              _.mockCheckMappingsTemplateExists(doesExist, checkedOnce),
              _.mockCheckSettingsTemplateExists(doesExist, checkedOnce),
              _.mockCheckIndexTemplateExists(doesExist, checkedOnce),
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCreateDataStream(Acknowledged),
            )
          )

          val auditDataStreamCreator = AuditDataStreamCreator.create(service)

          val result = auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
          result shouldBe Right(())
        }
        "lifecycle policy test" in {
          def createDataStreamService(indexLifecycleManagementMocks: Seq[MockFun]): DataStreamService = createMockedDataStreamService(
            List[MockFun](_.mockCheckDataStreamExists(doesNotExist, checkedOnce)) ++
              indexLifecycleManagementMocks ++
              List[MockFun](
                _.mockCheckMappingsTemplateExists(doesNotExist, checkedOnce),
                _.mockCreateMappingsTemplate(Acknowledged),
                _.mockCheckSettingsTemplateExists(doesNotExist, checkedOnce),
                _.mockCreateSettingsTemplate(Acknowledged),
                _.mockCheckIndexTemplateExists(doesNotExist, checkedOnce),
                _.mockCreateIndexTemplate(Acknowledged),
                _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
                _.mockCreateDataStream(Acknowledged),
              )
          )

          val createLifecyclePolicyImmediately: List[MockFun] = List(
            _.mockCheckLifecyclePolicyExists(doesNotExist, checkedOnce),
            _.mockCreateLifecyclePolicy(Acknowledged),
          )

          val createLifecyclePolicyWithQuery: List[MockFun] = {
            List[MockFun](
              _.mockCheckLifecyclePolicyExists(doesNotExist, checkedOnce),
              _.mockCreateLifecyclePolicy(NotAcknowledged),
              _.mockCheckLifecyclePolicyExists(doesExist, checkedOnce),
            )
          }

          def createLifecyclePolicyWithRetries(times: Int): List[MockFun] = {
            List[MockFun](
              _.mockCheckLifecyclePolicyExists(doesNotExist, checkedOnce),
              _.mockCreateLifecyclePolicy(NotAcknowledged),
              _.mockCheckLifecyclePolicyExists(doesNotExist, times),
              _.mockCheckLifecyclePolicyExists(doesExist, checkedOnce),
            )
          }

          val testCases: List[List[MockFun]] = List(
            createLifecyclePolicyImmediately,
            createLifecyclePolicyWithQuery,
            createLifecyclePolicyWithRetries(times = 1),
            createLifecyclePolicyWithRetries(times = 2),
            createLifecyclePolicyWithRetries(times = 3),
            createLifecyclePolicyWithRetries(times = 4),
            createLifecyclePolicyWithRetries(times = 5),
          )

          forEachTest(testCases) { indexLifecycleManagementMocks =>
            val service = createDataStreamService(indexLifecycleManagementMocks)
            val auditDataStreamCreator = AuditDataStreamCreator.create(service)

            val result = auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
            result shouldBe Right(())
          }
        }
        "component mappings tests" in {
          def createDataStreamService(componentMappingsMocks: Seq[MockFun]): DataStreamService = createMockedDataStreamService(
            List[MockFun](
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCheckLifecyclePolicyExists(doesExist, checkedOnce)
            ) ++
              componentMappingsMocks ++
              List[MockFun](
                _.mockCheckSettingsTemplateExists(doesNotExist, checkedOnce),
                _.mockCreateSettingsTemplate(Acknowledged),
                _.mockCheckIndexTemplateExists(doesNotExist, checkedOnce),
                _.mockCreateIndexTemplate(Acknowledged),
                _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
                _.mockCreateDataStream(Acknowledged),
              )
          )

          val createComponentMappingsImmediately: List[MockFun] = List(
            _.mockCheckMappingsTemplateExists(doesNotExist, checkedOnce),
            _.mockCreateMappingsTemplate(Acknowledged)
          )

          val createComponentMappingsWithQuery: List[MockFun] = {
            List[MockFun](
              _.mockCheckMappingsTemplateExists(doesNotExist, checkedOnce),
              _.mockCreateMappingsTemplate(NotAcknowledged),
              _.mockCheckMappingsTemplateExists(doesExist, checkedOnce)
            )
          }

          def createComponentMappingsWithRetries(times: Int): List[MockFun] = {
            List[MockFun](
              _.mockCheckMappingsTemplateExists(doesNotExist, checkedOnce),
              _.mockCreateMappingsTemplate(NotAcknowledged),
              _.mockCheckMappingsTemplateExists(doesNotExist, times),
              _.mockCheckMappingsTemplateExists(doesExist, checkedOnce)
            )
          }

          val testCases: List[List[MockFun]] = List(
            createComponentMappingsImmediately,
            createComponentMappingsWithQuery,
            createComponentMappingsWithRetries(checkedOnce),
            createComponentMappingsWithRetries(times = 2),
            createComponentMappingsWithRetries(times = 3),
            createComponentMappingsWithRetries(times = 4),
            createComponentMappingsWithRetries(times = 5),
          )

          forEachTest(testCases) { componentMappingsMocks =>
            val service = createDataStreamService(componentMappingsMocks)
            val auditDataStreamCreator = AuditDataStreamCreator.create(service)

            val result = auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
            result shouldBe Right(())
          }
        }
        "component settings test" in {
          def createDataStreamService(componentSettingsMocks: Seq[MockFun]): DataStreamService = createMockedDataStreamService(
            List[MockFun](
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCheckLifecyclePolicyExists(doesExist, checkedOnce),
              _.mockCheckMappingsTemplateExists(doesExist, checkedOnce)
            ) ++
              componentSettingsMocks ++
              List[MockFun](
                _.mockCheckIndexTemplateExists(doesNotExist, checkedOnce),
                _.mockCreateIndexTemplate(Acknowledged),
                _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
                _.mockCreateDataStream(Acknowledged),
              )
          )

          val alreadyExists: List[MockFun] = List(
            _.mockCheckSettingsTemplateExists(doesExist, checkedOnce),
          )

          val createComponentSettingsImmediately: List[MockFun] = List(
            _.mockCheckSettingsTemplateExists(doesNotExist, checkedOnce),
            _.mockCreateSettingsTemplate(Acknowledged),
          )

          val createComponentSettingsWithQuery: List[MockFun] = {
            List[MockFun](
              _.mockCheckSettingsTemplateExists(doesNotExist, checkedOnce),
              _.mockCreateSettingsTemplate(NotAcknowledged),
              _.mockCheckSettingsTemplateExists(doesExist, checkedOnce),
            )
          }

          def createComponentSettingsWithRetries(times: Int): List[MockFun] = {
            List[MockFun](
              _.mockCheckSettingsTemplateExists(doesNotExist, checkedOnce),
              _.mockCreateSettingsTemplate(NotAcknowledged),
              _.mockCheckSettingsTemplateExists(doesNotExist, times),
              _.mockCheckSettingsTemplateExists(doesExist, checkedOnce),
            )
          }

          val testCases: List[List[MockFun]] = List(
            alreadyExists,
            createComponentSettingsImmediately,
            createComponentSettingsWithQuery,
            createComponentSettingsWithRetries(1),
            createComponentSettingsWithRetries(2),
            createComponentSettingsWithRetries(3),
            createComponentSettingsWithRetries(4),
            createComponentSettingsWithRetries(5),
          )

          forEachTest(testCases) { componentMappingsMocks =>
            val service = createDataStreamService(componentMappingsMocks)
            val auditDataStreamCreator = AuditDataStreamCreator.create(service)

            val result = auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
            result shouldBe Right(())
          }
        }
        "index template test" in {
          def createDataStreamService(indexTemplateMocks: Seq[MockFun]): DataStreamService = createMockedDataStreamService(
            List[MockFun](
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCheckLifecyclePolicyExists(doesExist, checkedOnce),
              _.mockCheckMappingsTemplateExists(doesExist, checkedOnce),
              _.mockCheckSettingsTemplateExists(doesExist, checkedOnce),
            ) ++
              indexTemplateMocks ++
              List[MockFun](
                _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
                _.mockCreateDataStream(Acknowledged),
              )
          )

          val createIndexTemplateImmediately: List[MockFun] = List(
            _.mockCheckIndexTemplateExists(doesNotExist, checkedOnce),
            _.mockCreateIndexTemplate(Acknowledged),
          )

          val createIndexTemplateWithQuery: List[MockFun] = {
            List[MockFun](
              _.mockCheckIndexTemplateExists(doesNotExist, checkedOnce),
              _.mockCreateIndexTemplate(NotAcknowledged),
              _.mockCheckIndexTemplateExists(doesExist, checkedOnce),
            )
          }

          def createIndexTemplateWithRetries(times: Int): List[MockFun] = {
            List[MockFun](
              _.mockCheckIndexTemplateExists(doesNotExist, checkedOnce),
              _.mockCreateIndexTemplate(NotAcknowledged),
              _.mockCheckIndexTemplateExists(doesNotExist, times),
              _.mockCheckIndexTemplateExists(doesExist, checkedOnce),
            )
          }

          val testCases: List[List[MockFun]] = List(
            createIndexTemplateImmediately,
            createIndexTemplateWithQuery,
            createIndexTemplateWithRetries(1),
            createIndexTemplateWithRetries(2),
            createIndexTemplateWithRetries(3),
            createIndexTemplateWithRetries(4),
            createIndexTemplateWithRetries(5),
          )

          forEachTest(testCases) { componentMappingsMocks =>
            val service = createDataStreamService(componentMappingsMocks)
            val auditDataStreamCreator = AuditDataStreamCreator.create(service)

            val result = auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
            result shouldBe Right(())
          }
        }
        "data stream test" in {
          def createDataStreamService(dataStreamMocks: Seq[MockFun]): DataStreamService = createMockedDataStreamService(
            List[MockFun](
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCheckLifecyclePolicyExists(doesExist, checkedOnce),
              _.mockCheckMappingsTemplateExists(doesExist, checkedOnce),
              _.mockCheckSettingsTemplateExists(doesExist, checkedOnce),
              _.mockCheckIndexTemplateExists(doesExist, checkedOnce),
            ) ++
              dataStreamMocks
          )

          val createDataStreamImmediately: List[MockFun] = List(
            _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
            _.mockCreateDataStream(Acknowledged),
          )

          val createDataStreamWithQuery: List[MockFun] = {
            List[MockFun](
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCreateDataStream(NotAcknowledged),
              _.mockCheckDataStreamExists(doesExist, checkedOnce)
            )
          }

          def createDataStreamWithRetries(times: Int): List[MockFun] = {
            List[MockFun](
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCreateDataStream(NotAcknowledged),
              _.mockCheckDataStreamExists(doesNotExist, times),
              _.mockCheckDataStreamExists(doesExist, checkedOnce)
            )
          }

          val testCases: List[List[MockFun]] = List(
            createDataStreamImmediately,
            createDataStreamWithQuery,
            createDataStreamWithRetries(1),
            createDataStreamWithRetries(2),
            createDataStreamWithRetries(3),
            createDataStreamWithRetries(4),
            createDataStreamWithRetries(5),
          )

          forEachTest(testCases) { componentMappingsMocks =>
            val service = createDataStreamService(componentMappingsMocks)
            val auditDataStreamCreator = AuditDataStreamCreator.create(service)

            val result = auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
            result shouldBe Right(())
          }
        }
      }
      "attempt to create data stream and fail" when {
        "lifecycle policy creation fails" in {
          def createDataStreamService(indexLifecycleManagementMocks: Seq[MockFun]): DataStreamService = createMockedDataStreamService(
            List[MockFun](_.mockCheckDataStreamExists(doesNotExist, checkedOnce)) ++
              indexLifecycleManagementMocks
          )

          val failAfterExhaustedRetries = List[MockFun](
            _.mockCheckLifecyclePolicyExists(doesNotExist, checkedOnce),
            _.mockCreateLifecyclePolicy(NotAcknowledged),
            _.mockCheckLifecyclePolicyExists(doesNotExist, times = 6),
          )

          val testCases: List[List[MockFun]] = List(
            failAfterExhaustedRetries
          )

          forEachTest(testCases) { indexLifecycleManagementMocks =>
            val service = createDataStreamService(indexLifecycleManagementMocks)
            val auditDataStreamCreator = AuditDataStreamCreator.create(service)

            val expectedFailureReason = s"Unable to determine if the index lifecycle policy with ID '${expectedLifecyclePolicyName.value}' has been created"

            val result = auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
            result shouldBe Left(NonEmptyList.of(ErrorMessage(s"Failed to setup ROR audit data stream readonlyrest_audit. Reason: $expectedFailureReason")))
          }
        }
        "component mappings creation fails" in {
          def createDataStreamService(componentMappingsMocks: Seq[MockFun]): DataStreamService = createMockedDataStreamService(
            List[MockFun](
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCheckLifecyclePolicyExists(doesExist, checkedOnce)
            ) ++
              componentMappingsMocks
          )

          val failAfterExhaustedRetries =
            List[MockFun](
              _.mockCheckMappingsTemplateExists(doesNotExist, checkedOnce),
              _.mockCreateMappingsTemplate(NotAcknowledged),
              _.mockCheckMappingsTemplateExists(doesNotExist, times = 6),
            )

          val testCases: List[List[MockFun]] = List(
            failAfterExhaustedRetries
          )

          forEachTest(testCases) { indexLifecycleManagementMocks =>
            val service = createDataStreamService(indexLifecycleManagementMocks)
            val auditDataStreamCreator = AuditDataStreamCreator.create(service)

            val expectedFailureReason = s"Unable to determine if component template with ID 'readonlyrest_audit-mappings' has been created"

            val result = auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
            result shouldBe Left(NonEmptyList.of(ErrorMessage(s"Failed to setup ROR audit data stream readonlyrest_audit. Reason: $expectedFailureReason")))
          }
        }
        "component settings creation fails" in {
          def createDataStreamService(componentSettingsMocks: Seq[MockFun]): DataStreamService = createMockedDataStreamService(
            List[MockFun](
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCheckLifecyclePolicyExists(doesExist, checkedOnce),
              _.mockCheckMappingsTemplateExists(doesExist, checkedOnce),
            ) ++
              componentSettingsMocks
          )

          val failAfterExhaustedRetries =
            List[MockFun](
              _.mockCheckSettingsTemplateExists(doesNotExist, checkedOnce),
              _.mockCreateSettingsTemplate(NotAcknowledged),
              _.mockCheckSettingsTemplateExists(doesNotExist, times = 6),
            )

          val testCases: List[List[MockFun]] = List(
            failAfterExhaustedRetries
          )

          forEachTest(testCases) { componentSettingsMocks =>
            val service = createDataStreamService(componentSettingsMocks)
            val auditDataStreamCreator = AuditDataStreamCreator.create(service)

            val expectedFailureReason = "Unable to determine if component template with ID 'readonlyrest_audit-settings' has been created"

            val result = auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
            result shouldBe Left(NonEmptyList.of(ErrorMessage(s"Failed to setup ROR audit data stream readonlyrest_audit. Reason: $expectedFailureReason")))
          }
        }
        "index template creation fails" in {
          def createDataStreamService(indexTemplateMocks: Seq[MockFun]): DataStreamService = createMockedDataStreamService(
            List[MockFun](
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCheckLifecyclePolicyExists(doesExist, checkedOnce),
              _.mockCheckMappingsTemplateExists(doesExist, checkedOnce),
              _.mockCheckSettingsTemplateExists(doesExist, checkedOnce),
            ) ++
              indexTemplateMocks
          )

          val failAfterExhaustedRetries =
            List[MockFun](
              _.mockCheckIndexTemplateExists(doesNotExist, checkedOnce),
              _.mockCreateIndexTemplate(NotAcknowledged),
              _.mockCheckIndexTemplateExists(doesNotExist, times = 6),
            )

          val testCases: List[List[MockFun]] = List(
            failAfterExhaustedRetries
          )

          forEachTest(testCases) { indexTemplateMocks =>
            val service = createDataStreamService(indexTemplateMocks)
            val auditDataStreamCreator = AuditDataStreamCreator.create(service)

            val expectedFailureReason = "Unable to determine if index template with ID 'readonlyrest_audit-template' has been created"

            val result = auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
            result shouldBe Left(NonEmptyList.of(ErrorMessage(s"Failed to setup ROR audit data stream readonlyrest_audit. Reason: $expectedFailureReason")))
          }
        }
        "data stream creation fails" in {
          def createDataStreamService(dataStreamMocks: Seq[MockFun]): DataStreamService = createMockedDataStreamService(
            List[MockFun](
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCheckLifecyclePolicyExists(doesExist, checkedOnce),
              _.mockCheckMappingsTemplateExists(doesExist, checkedOnce),
              _.mockCheckSettingsTemplateExists(doesExist, checkedOnce),
              _.mockCheckIndexTemplateExists(doesExist, checkedOnce)
            ) ++
              dataStreamMocks
          )

          val failAfterExhaustedRetries =
            List[MockFun](
              _.mockCheckDataStreamExists(doesNotExist, checkedOnce),
              _.mockCreateDataStream(NotAcknowledged),
              _.mockCheckDataStreamExists(doesNotExist, times = 6),
            )

          val testCases: List[List[MockFun]] = List(
            failAfterExhaustedRetries
          )

          forEachTest(testCases) { indexTemplateMocks =>
            val service = createDataStreamService(indexTemplateMocks)
            val auditDataStreamCreator = AuditDataStreamCreator.create(service)

            val expectedFailureReason = "Unable to determine if data stream with ID 'readonlyrest_audit' has been created"

            val result = auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
            result shouldBe Left(NonEmptyList.of(ErrorMessage(s"Failed to setup ROR audit data stream readonlyrest_audit. Reason: $expectedFailureReason")))
          }
        }
      }

    }
  }

  private def forEachTest[A](testInputs: List[A])(test: A => Unit): Unit = {
    testInputs.zipWithIndex.foreach { case (testInput, idx) =>
      withClue(s"Failed test for input with idx $idx") {
        test(testInput)
      }
    }
  }

  private def createMockedDataStreamService(mocksSequence: List[MockFun]): DataStreamService = {
    val service: MockableDataStreamService = mock[MockableDataStreamService]

    inSequence {
      mocksSequence.foreach {
        _.apply(service)
      }
    }

    service
  }

  extension (c: AuditDataStreamCreator.type) {
    def create(service: DataStreamService): AuditDataStreamCreator = AuditDataStreamCreator(NonEmptyList.of(service))
  }

  extension (service: MockableDataStreamService) {
    def mockCheckLifecyclePolicyExists(result: Boolean, times: Int): Unit = {
      (service.ilmPolicyExists _)
        .expects(expectedLifecyclePolicyName)
        .returns(result)
        .repeated(times)
    }

    def mockCreateLifecyclePolicy(result: CreationResult): Unit = {
      (service.createIlmPolicy _)
        .expects {
          where { (settings: DataStreamSettings.LifecyclePolicy) =>
            settings.id == expectedLifecyclePolicyName
          }
        }
        .returns(result)
        .once()
    }

    def mockCheckMappingsTemplateExists(result: Boolean, times: Int): Unit = {
      (service.componentTemplateExists _)
        .expects(expectedMappingsTemplateName)
        .returns(result)
        .repeated(times)
    }

    def mockCreateMappingsTemplate(result: CreationResult): Unit = {
      (service.createMappingsTemplate _)
        .expects {
          where { (settings: DataStreamSettings.ComponentTemplateMappings) =>
            settings.templateName == expectedMappingsTemplateName
          }
        }
        .returns(result)
        .once()
    }


    def mockCheckSettingsTemplateExists(result: Boolean, times: Int): Unit = {
      (service.componentTemplateExists _)
        .expects(expectedSettingsTemplateName)
        .returns(result)
        .repeated(times)
    }

    def mockCreateSettingsTemplate(result: CreationResult): Unit = {
      (service.createSettingsTemplate _)
        .expects {
          where { (settings: DataStreamSettings.ComponentTemplateSettings) =>
            settings.templateName == expectedSettingsTemplateName
          }
        }
        .returns(result)
        .once()
    }


    def mockCheckIndexTemplateExists(result: Boolean, times: Int): Unit = {
      (service.indexTemplateExists _)
        .expects(expectedIndexTemplateName)
        .returns(result)
        .repeated(times)
    }

    def mockCreateIndexTemplate(result: CreationResult): Unit = {
      (service.createTemplate _)
        .expects {
          where { (settings: DataStreamSettings.IndexTemplateSettings) =>
            settings.templateName == expectedIndexTemplateName
          }
        }
        .returns(result)
        .once()
    }

    def mockCheckDataStreamExists(result: Boolean, times: Int): Unit = {
      (service.dataStreamExists _)
        .expects(auditDs.dataStream)
        .returns(result)
        .repeated(times)
    }

    def mockCreateDataStream(result: CreationResult): Unit = {
      (service.createStream _)
        .expects(auditDs.dataStream)
        .returns(result)
        .once()
    }

  }

  class MockableDataStreamService extends DataStreamService {

    override protected val retryConfig: RetryConfig = RetryConfig(
      initialDelay = 1.milliseconds, backoffScaler = 1, maxRetries = 5
    )

    def dataStreamExists(dataStreamName: DataStreamName.Full): Boolean =
      throwErrorWhenNotMocked

    def ilmPolicyExists(policyId: NonEmptyString): Boolean =
      throwErrorWhenNotMocked

    def createIlmPolicy(policy: DataStreamSettings.LifecyclePolicy): CreationResult =
      throwErrorWhenNotMocked

    def componentTemplateExists(templateName: TemplateName): Boolean =
      throwErrorWhenNotMocked

    def createMappingsTemplate(settings: DataStreamSettings.ComponentTemplateMappings): CreationResult =
      throwErrorWhenNotMocked

    def createSettingsTemplate(settings: DataStreamSettings.ComponentTemplateSettings): CreationResult =
      throwErrorWhenNotMocked

    def indexTemplateExists(templateName: TemplateName): Boolean =
      throwErrorWhenNotMocked

    def createTemplate(settings: DataStreamSettings.IndexTemplateSettings): CreationResult =
      throwErrorWhenNotMocked

    def createStream(dataStreamName: DataStreamName.Full): CreationResult =
      throwErrorWhenNotMocked

    override final def checkDataStreamExists(dataStreamName: DataStreamName.Full): Task[Boolean] =
      Task.delay(dataStreamExists(dataStreamName))

    override protected final def createDataStream(dataStreamName: DataStreamName.Full): Task[CreationResult] =
      Task.delay(createStream(dataStreamName))

    override protected final def checkIndexLifecyclePolicyExists(policyId: NonEmptyString): Task[Boolean] =
      Task.delay(ilmPolicyExists(policyId))

    override protected final def createIndexLifecyclePolicy(policy: DataStreamSettings.LifecyclePolicy): Task[CreationResult] =
      Task.delay(createIlmPolicy(policy))

    override protected final def checkComponentTemplateExists(templateName: TemplateName): Task[Boolean] =
      Task.delay(componentTemplateExists(templateName))

    override protected final def createComponentTemplateForMappings(settings: DataStreamSettings.ComponentTemplateMappings): Task[CreationResult] =
      Task.delay(createMappingsTemplate(settings))

    override protected final def createComponentTemplateForIndex(settings: DataStreamSettings.ComponentTemplateSettings): Task[CreationResult] =
      Task.delay(createSettingsTemplate(settings))

    override protected final def checkIndexTemplateExists(templateName: TemplateName): Task[Boolean] =
      Task.delay(indexTemplateExists(templateName))

    override protected final def createIndexTemplate(settings: DataStreamSettings.IndexTemplateSettings): Task[CreationResult] =
      Task.delay(createTemplate(settings))

    private def throwErrorWhenNotMocked: Nothing = throw new IllegalStateException("Method was not mocked")
  }

}

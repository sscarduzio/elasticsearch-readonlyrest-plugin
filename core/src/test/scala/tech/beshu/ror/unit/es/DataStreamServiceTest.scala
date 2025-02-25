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

  "A ReadonlyREST data stream service" when {
    "fully setup data stream called" should {
      "not attempt to create data stream when one exists" in {
        tryToCreateDataStream(DataStreamsMocks.alreadyExists)
      }
      "attempt to create data stream with success" when {
        "all resources available immediately" in {
          testSuccessfulDataStreamSetup(List(
            IlmMocks.alreadyExists,
            ComponentMappingsMocks.alreadyExists,
            ComponentSettingsMocks.alreadyExists,
            IndexTemplateMocks.alreadyExists,
            DataStreamsMocks.createDataStreamImmediately
          ).flatten)
        }
        "lifecycle policy " in {
          def testScenario(indexLifecycleManagementMocks: List[MockFun]) = {
            indexLifecycleManagementMocks ++
              ComponentMappingsMocks.createComponentMappingsImmediately ++
              ComponentSettingsMocks.createComponentSettingsImmediately ++
              IndexTemplateMocks.createIndexTemplateImmediately ++
              DataStreamsMocks.createDataStreamImmediately
          }

          val testCases: List[List[MockFun]] = List(
            IlmMocks.createLifecyclePolicyImmediately,
            IlmMocks.createLifecyclePolicyWithQuery,
            IlmMocks.createLifecyclePolicyWithRetries(1),
            IlmMocks.createLifecyclePolicyWithRetries(2),
            IlmMocks.createLifecyclePolicyWithRetries(3),
            IlmMocks.createLifecyclePolicyWithRetries(4),
            IlmMocks.createLifecyclePolicyWithRetries(5),
          )

          runTests(testCases) {
            input => testSuccessfulDataStreamSetup(testScenario(input))
          }
        }
        "component mappings tests" in {
          def testScenario(componentMappingsMocks: List[MockFun]): List[MockFun] = {
            IlmMocks.alreadyExists ++
              componentMappingsMocks ++
              ComponentSettingsMocks.createComponentSettingsImmediately ++
              IndexTemplateMocks.createIndexTemplateImmediately ++
              DataStreamsMocks.createDataStreamImmediately
          }

          val testCases: List[List[MockFun]] = List(
            ComponentMappingsMocks.createComponentMappingsImmediately,
            ComponentMappingsMocks.createComponentMappingsWithQuery,
            ComponentMappingsMocks.createComponentMappingsWithRetries(1),
            ComponentMappingsMocks.createComponentMappingsWithRetries(2),
            ComponentMappingsMocks.createComponentMappingsWithRetries(3),
            ComponentMappingsMocks.createComponentMappingsWithRetries(4),
            ComponentMappingsMocks.createComponentMappingsWithRetries(5),
          )

          runTests(testCases) {
            input => testSuccessfulDataStreamSetup(testScenario(input))
          }
        }
        "component settings test" in {
          def testScenario(componentSettingsMocks: List[MockFun]): List[MockFun] = {
            IlmMocks.alreadyExists ++
              ComponentMappingsMocks.alreadyExists ++
              componentSettingsMocks ++
              IndexTemplateMocks.createIndexTemplateImmediately ++
              DataStreamsMocks.createDataStreamImmediately
          }

          val testCases: List[List[MockFun]] = List(
            ComponentSettingsMocks.alreadyExists,
            ComponentSettingsMocks.createComponentSettingsImmediately,
            ComponentSettingsMocks.createComponentSettingsWithQuery,
            ComponentSettingsMocks.createComponentSettingsWithRetries(1),
            ComponentSettingsMocks.createComponentSettingsWithRetries(2),
            ComponentSettingsMocks.createComponentSettingsWithRetries(3),
            ComponentSettingsMocks.createComponentSettingsWithRetries(4),
            ComponentSettingsMocks.createComponentSettingsWithRetries(5),
          )

          runTests(testCases) {
            input => testSuccessfulDataStreamSetup(testScenario(input))
          }
        }
        "index template test" in {
          def testScenario(indexTemplateMocks: List[MockFun]): List[MockFun] = {
            IlmMocks.alreadyExists ++
              ComponentMappingsMocks.alreadyExists ++
              ComponentSettingsMocks.alreadyExists ++
              indexTemplateMocks ++
              DataStreamsMocks.createDataStreamImmediately
          }

          val testCases: List[List[MockFun]] = List(
            IndexTemplateMocks.createIndexTemplateImmediately,
            IndexTemplateMocks.createIndexTemplateWithQuery,
            IndexTemplateMocks.createIndexTemplateWithRetries(1),
            IndexTemplateMocks.createIndexTemplateWithRetries(2),
            IndexTemplateMocks.createIndexTemplateWithRetries(3),
            IndexTemplateMocks.createIndexTemplateWithRetries(4),
            IndexTemplateMocks.createIndexTemplateWithRetries(5),
          )

          runTests(testCases) { input =>
            testSuccessfulDataStreamSetup(testScenario(input))
          }
        }
        "data stream test" in {
          def testScenario(dataStreamMocks: List[MockFun]): List[MockFun] = {
            IlmMocks.alreadyExists ++
              ComponentMappingsMocks.alreadyExists ++
              ComponentSettingsMocks.alreadyExists ++
              IndexTemplateMocks.alreadyExists ++
              dataStreamMocks
          }

          val testCases: List[List[MockFun]] = List(
            DataStreamsMocks.createDataStreamImmediately,
            DataStreamsMocks.createDataStreamWithQuery,
            DataStreamsMocks.createDataStreamWithRetries(1),
            DataStreamsMocks.createDataStreamWithRetries(2),
            DataStreamsMocks.createDataStreamWithRetries(3),
            DataStreamsMocks.createDataStreamWithRetries(4),
            DataStreamsMocks.createDataStreamWithRetries(5),
          )

          runTests(testCases) { input =>
            testSuccessfulDataStreamSetup(testScenario(input))
          }
        }
      }
      "attempt to create data stream and fail" when {
        "lifecycle policy creation fails" in {
          val testScenarios = List(
            IlmMocks.createLifecyclePolicyWithRetries(6, successful = false)
          )

          runTests(testScenarios) { input =>
            testFailingDataStreamSetup(input, "Unable to determine if the index lifecycle policy with ID 'readonlyrest_audit-lifecycle-policy' has been created")
          }
        }
        "component mappings creation fails" in {
          val testScenarios = List(
            IlmMocks.alreadyExists ++
              ComponentMappingsMocks.createComponentMappingsWithRetries(6, successful = false)
          )

          runTests(testScenarios) { input =>
            testFailingDataStreamSetup(input, "Unable to determine if component template with ID 'readonlyrest_audit-mappings' has been created")
          }
        }
        "component settings creation fails" in {
          val testScenarios = List(
            IlmMocks.alreadyExists ++
              ComponentMappingsMocks.alreadyExists ++
              ComponentSettingsMocks.createComponentSettingsWithRetries(6, successful = false)
          )

          runTests(testScenarios) { input =>
            testFailingDataStreamSetup(input, "Unable to determine if component template with ID 'readonlyrest_audit-settings' has been created")
          }
        }
        "index template creation fails" in {
          val testScenarios = List(
            IlmMocks.alreadyExists ++
              ComponentMappingsMocks.alreadyExists ++
              ComponentSettingsMocks.alreadyExists ++
              IndexTemplateMocks.createIndexTemplateWithRetries(6, successful = false)
          )

          runTests(testScenarios) { input =>
            testFailingDataStreamSetup(input, "Unable to determine if index template with ID 'readonlyrest_audit-template' has been created")
          }
        }
        "data stream creation fails" in {
          val testScenarios = List(
            IlmMocks.alreadyExists ++
              ComponentMappingsMocks.alreadyExists ++
              ComponentSettingsMocks.alreadyExists ++
              IndexTemplateMocks.alreadyExists ++
              DataStreamsMocks.createDataStreamWithRetries(6, successful = false)
          )

          runTests(testScenarios) { input =>
            testFailingDataStreamSetup(input, "Unable to determine if data stream with ID 'readonlyrest_audit' has been created")
          }
        }
      }

    }
  }

  private def runTests[A](testInputs: List[A])(test: A => Unit): Unit = {
    testInputs.zipWithIndex.foreach { case (testInput, idx) =>
      withClue(s"Failed test for input with idx $idx") {
        test(testInput)
      }
    }
  }

  private def testSuccessfulDataStreamSetup(mocksSequence: List[MockFun]): Unit = {
    val result = tryToCreateDataStream(
      (DataStreamsMocks.doesNotExist ++ mocksSequence)
    )
    result shouldBe Right(())
  }

  private def testFailingDataStreamSetup(mocksSequence: List[MockFun], expectedFailureReason: String): Unit = {
    val result = tryToCreateDataStream(
      (DataStreamsMocks.doesNotExist ++ mocksSequence)
    )
    result shouldBe Left(s"Failed to setup ROR audit data stream readonlyrest_audit. Reason: $expectedFailureReason")
  }

  private def tryToCreateDataStream(mocksSequence: List[MockFun]): Either[String, Unit] = {
    val service: MockableDataStreamService = mock[MockableDataStreamService]

    inSequence {
      mocksSequence.foreach {
        _.apply(service)
      }
    }

    val auditDataStreamCreator = AuditDataStreamCreator(NonEmptyList.of(service))
    auditDataStreamCreator.createIfNotExists(auditDs).runSyncUnsafe()
  }

  private object IlmMocks {
    def alreadyExists: List[MockFun] = {
      List(_.mockCheckLifecyclePolicyExists(true, 1))
    }

    def createLifecyclePolicyImmediately: List[MockFun] = List(
      _.mockCheckLifecyclePolicyExists(false, 1),
      _.mockCreateLifecyclePolicy(Acknowledged),
    )

    def createLifecyclePolicyWithQuery: List[MockFun] = {
      List[MockFun](
        _.mockCheckLifecyclePolicyExists(false, 1),
        _.mockCreateLifecyclePolicy(NotAcknowledged),
      ) ++ alreadyExists
    }

    def createLifecyclePolicyWithRetries(times: Int, successful: Boolean = true): List[MockFun] = {
      List[MockFun](
        _.mockCheckLifecyclePolicyExists(false, 1),
        _.mockCreateLifecyclePolicy(NotAcknowledged),
        _.mockCheckLifecyclePolicyExists(false, times),
      ) ++ alreadyExists.filter(_ => successful)
    }
  }

  private object ComponentMappingsMocks {
    def alreadyExists: List[MockFun] = {
      List(_.mockCheckMappingsTemplateExists(true, 1))
    }

    def createComponentMappingsImmediately: List[MockFun] = List(
      _.mockCheckMappingsTemplateExists(false, 1),
      _.mockCreateMappingsTemplate(Acknowledged)
    )

    def createComponentMappingsWithQuery: List[MockFun] = {
      List[MockFun](
        _.mockCheckMappingsTemplateExists(false, 1),
        _.mockCreateMappingsTemplate(NotAcknowledged),
      ) ++ alreadyExists
    }

    def createComponentMappingsWithRetries(times: Int, successful: Boolean = true): List[MockFun] = {
      List[MockFun](
        _.mockCheckMappingsTemplateExists(false, 1),
        _.mockCreateMappingsTemplate(NotAcknowledged),
        _.mockCheckMappingsTemplateExists(false, times),
      ) ++ alreadyExists.filter(_ => successful)
    }
  }

  private object ComponentSettingsMocks {
    def alreadyExists: List[MockFun] = {
      List(_.mockCheckSettingsTemplateExists(true, 1))
    }

    def createComponentSettingsImmediately: List[MockFun] = List(
      _.mockCheckSettingsTemplateExists(false, 1),
      _.mockCreateSettingsTemplate(Acknowledged),
    )

    def createComponentSettingsWithQuery: List[MockFun] = {
      List[MockFun](
        _.mockCheckSettingsTemplateExists(false, 1),
        _.mockCreateSettingsTemplate(NotAcknowledged),
      ) ++ alreadyExists
    }

    def createComponentSettingsWithRetries(times: Int, successful: Boolean = true): List[MockFun] = {
      List[MockFun](
        _.mockCheckSettingsTemplateExists(false, 1),
        _.mockCreateSettingsTemplate(NotAcknowledged),
        _.mockCheckSettingsTemplateExists(false, times),
      ) ++ alreadyExists.filter(_ => successful)
    }
  }

  private object IndexTemplateMocks {
    def alreadyExists: List[MockFun] = {
      List(_.mockCheckIndexTemplateExists(true, 1))
    }

    def createIndexTemplateImmediately: List[MockFun] = List(
      _.mockCheckIndexTemplateExists(false, 1),
      _.mockCreateIndexTemplate(Acknowledged),
    )

    def createIndexTemplateWithQuery: List[MockFun] = {
      List[MockFun](
        _.mockCheckIndexTemplateExists(false, 1),
        _.mockCreateIndexTemplate(NotAcknowledged),
      ) ++ alreadyExists
    }

    def createIndexTemplateWithRetries(times: Int, successful: Boolean = true): List[MockFun] = {
      List[MockFun](
        _.mockCheckIndexTemplateExists(false, 1),
        _.mockCreateIndexTemplate(NotAcknowledged),
        _.mockCheckIndexTemplateExists(false, times),
      ) ++ alreadyExists.filter(_ => successful)
    }
  }

  private object DataStreamsMocks {
    def doesNotExist: List[MockFun] = {
      List[MockFun](_.mockCheckDataStreamExists(false, 1))
    }

    def alreadyExists: List[MockFun] = {
      List(_.mockCheckDataStreamExists(true, 1))
    }

    def createDataStreamImmediately: List[MockFun] = List(
      _.mockCheckDataStreamExists(false, 1),
      _.mockCreateDataStream(Acknowledged),
    )

    def createDataStreamWithQuery: List[MockFun] = {
      List[MockFun](
        _.mockCheckDataStreamExists(false, 1),
        _.mockCreateDataStream(NotAcknowledged),
      ) ++ alreadyExists
    }

    def createDataStreamWithRetries(times: Int, successful: Boolean = true): List[MockFun] = {
      List[MockFun](
        _.mockCheckDataStreamExists(false, 1),
        _.mockCreateDataStream(NotAcknowledged),
        _.mockCheckDataStreamExists(false, times),
      ) ++ alreadyExists.filter(_ => successful)
    }
  }

  extension (service: MockableDataStreamService) {
    def mockCheckLifecyclePolicyExists(result: Boolean, times: Int = 1): Unit = {
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

    def mockCheckMappingsTemplateExists(result: Boolean, times: Int = 1): Unit = {
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


    def mockCheckSettingsTemplateExists(result: Boolean, times: Int = 1): Unit = {
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


    def mockCheckIndexTemplateExists(result: Boolean, times: Int = 1): Unit = {
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

    def mockCheckDataStreamExists(result: Boolean, times: Int = 1): Unit = {
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

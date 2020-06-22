package tech.beshu.ror.integration.suites.base

import org.scalatest.Matchers._
import org.scalatest._
import tech.beshu.ror.utils.containers.providers.{MultipleClients, MultipleEsTargets}
import tech.beshu.ror.utils.elasticsearch.ClusterManager

import scala.util.{Failure, Success, Try}

trait TestSuiteWithClosedTaskAssertion extends TestSuite {
  this: MultipleClients with MultipleEsTargets =>

  private val adminClusterManager = new ClusterManager(
    clients.head.basicAuthClient("admin", "container"),
    esVersion = esTargets.head.esVersion
  )

  override def withFixture(test: NoArgTest): Outcome = {
    super.withFixture(test) match {
      case exceptional: Exceptional => exceptional
      case Succeeded =>
        val tasks = adminClusterManager.tasks().results
        Try {
          tasks.length should be(2)
          tasks.map(_("action").str).toSet should be (Set("cluster:monitor/tasks/lists", "cluster:monitor/tasks/lists[n]"))
        } match {
          case Failure(exception) => Failed(exception)
          case Success(_) => Succeeded
        }
      case Pending => Pending
    }
  }

}

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
package tech.beshu.ror.integration.suites.base

import org.scalatest.Matchers._
import org.scalatest._
import tech.beshu.ror.utils.containers.providers.{MultipleClients, MultipleEsTargets}
import tech.beshu.ror.utils.elasticsearch.ClusterManager

import scala.util.{Failure, Success, Try}

trait TestSuiteWithClosedTaskAssertion extends TestSuite {
  this: MultipleClients with MultipleEsTargets =>

  private lazy val adminClusterManager = new ClusterManager(
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

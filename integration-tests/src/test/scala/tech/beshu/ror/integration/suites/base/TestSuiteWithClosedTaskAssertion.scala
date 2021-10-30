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

import org.scalatest._
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.providers.{MultipleClients, MultipleEsTargets}
import tech.beshu.ror.utils.elasticsearch.CatManager
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

import scala.util.{Failure, Success, Try}

trait TestSuiteWithClosedTaskAssertion extends TestSuite with CustomScalaTestMatchers {
  this: MultipleClients with MultipleEsTargets with ESVersionSupport =>

  private lazy val adminCatManager = new CatManager(
    clients.head.basicAuthClient("admin", "container"),
    esVersion = esVersionUsed
  )

  override def withFixture(test: NoArgTest): Outcome = {
    super.withFixture(test) match {
      case exceptional: Exceptional => exceptional
      case Succeeded =>
        val tasks = adminCatManager.tasks().results
        Try {
          tasks.map(_ ("action").str).toSet should notContainElementsFrom(Set(
            """^cluster:ror/.*$""".r
          ))
        } match {
          case Failure(exception) => Failed(exception)
          case Success(_) => Succeeded
        }
      case Pending => Pending
    }
  }

}

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
package tech.beshu.ror.utils.containers

import com.dimafeng.testcontainers.SingleContainer
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.testcontainers.containers.GenericContainer

object DependencyRunner {

  final case class EvaluatedDependency(name: String, container: SingleContainer[GenericContainer[_]], originalPort: Int)

  def startDependencies(definitions: List[DependencyDef]): StartedClusterDependencies = {
    val evaluatedDependencies = evaluate(definitions)
    startContainersAsynchronously(evaluatedDependencies)
    convertToStartedDependencies(evaluatedDependencies)
  }

  private def evaluate(definitions: List[DependencyDef]): List[EvaluatedDependency] = {
    definitions.map(d => EvaluatedDependency(d.name, d.containerCreator.apply(), d.originalPort))
  }

  private def startContainersAsynchronously(dependencies: List[EvaluatedDependency]): Unit = {
    Task
      .parSequenceUnordered {
        dependencies.map(dependency => Task(dependency.container.start()))
      }
      .runSyncUnsafe()
  }

  private def convertToStartedDependencies(dependencies: List[EvaluatedDependency]) = {
    StartedClusterDependencies {
      dependencies
        .map { dependency =>
          StartedDependency(dependency.name, dependency.container, dependency.originalPort)
        }
    }
  }
}

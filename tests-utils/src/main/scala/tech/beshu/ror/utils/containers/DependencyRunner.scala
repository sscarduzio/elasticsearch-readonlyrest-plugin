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
      .gatherUnordered {
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

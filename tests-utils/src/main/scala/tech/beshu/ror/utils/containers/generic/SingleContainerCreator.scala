package tech.beshu.ror.utils.containers.generic

import cats.data.NonEmptyList
import monix.eval.Task

trait SingleContainerCreator {

  def create(name: String, nodeNames: NonEmptyList[String], clusterSettings: ClusterSettings): RorContainer
}

package tech.beshu.ror.integration

import com.dimafeng.testcontainers.{Container, ForAllTestContainer}
import org.scalatest.WordSpec
import tech.beshu.ror.integration.utils.ReadonlyRestEsClusterContainer

class CrossClusterSearchTests extends WordSpec with ForAllTestContainer {

  override val container: Container = ReadonlyRestEsClusterContainer.create("/cross_cluster_search/readonlyrest.yml")

  "A test" in {
    container.hashCode()
  }
}

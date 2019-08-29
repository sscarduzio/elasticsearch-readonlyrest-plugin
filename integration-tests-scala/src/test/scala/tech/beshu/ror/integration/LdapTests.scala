package tech.beshu.ror.integration

import com.dimafeng.testcontainers.{Container, ForAllTestContainer}
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.ReadonlyRestEsCluster

class LdapTests extends WordSpec with ForAllTestContainer {

  override val container: Container = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/ldap/readonlyrest.yml",
    numberOfInstances = 1
  )
  "A test" in {
    val i = 0
  }
}

package tech.beshu.ror.utils.containers.steatment

import org.testcontainers.images.builder.dockerfile.statement.{RawStatement, Statement}
import org.testcontainers.utility.DockerImageName

object FromAsStatement {
  def apply(dockerImageName: String, tag: String): Statement = {
    new DockerImageName(dockerImageName).assertValid()
    new RawStatement("FROM", s"$dockerImageName AS $tag")
  }
}

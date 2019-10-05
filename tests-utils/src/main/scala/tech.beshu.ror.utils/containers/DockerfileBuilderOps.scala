package tech.beshu.ror.utils.containers

import org.testcontainers.images.builder.dockerfile.DockerfileBuilder

object DockerfileBuilderOps {

  implicit class OptionalCommandRunner(val builder: DockerfileBuilder) extends AnyVal {

    def runWhen(condition: Boolean, command: String): DockerfileBuilder = {
      if (condition) builder.run(command)
      else builder
    }
  }
}

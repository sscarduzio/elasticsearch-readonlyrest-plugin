package tech.beshu.ror.integration.utils

import com.dimafeng.testcontainers.Container
import org.junit.runner.Description

class MultipleContainers(containers: Seq[Container]) extends Container {

  override def finished()(implicit description: Description): Unit = containers.foreach(_.finished()(description))

  override def succeeded()(implicit description: Description): Unit = containers.foreach(_.succeeded()(description))

  override def starting()(implicit description: Description): Unit = containers.foreach(_.starting()(description))

  override def failed(e: Throwable)(implicit description: Description): Unit = containers.foreach(_.failed(e)(description))
}

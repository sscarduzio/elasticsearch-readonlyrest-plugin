package tech.beshu.ror.integration.utils

object JavaScalaUtils {

  def bracket[A <: AutoCloseable,B](closeableAction: A)(map: A => B): B = {
    try {
      map(closeableAction)
    } finally {
      closeableAction.close()
    }
  }
}

package tech.beshu.ror.es.utils

import java.security.{AccessController, PrivilegedAction}

object AccessControllerHelper {

  def doPrivileged[T](action: => T): T = {
    AccessController.doPrivileged(new PrivilegedAction[T] {
      override def run(): T = action
    })
  }
}

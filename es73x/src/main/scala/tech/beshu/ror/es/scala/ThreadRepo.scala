package tech.beshu.ror.es.scala

import org.elasticsearch.rest.RestChannel

object ThreadRepo {
  val channel = new ThreadLocal[RestChannel]
}

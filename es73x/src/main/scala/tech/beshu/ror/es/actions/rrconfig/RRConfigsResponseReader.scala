package tech.beshu.ror.es.actions.rrconfig

import org.elasticsearch.common.io.stream.{StreamInput, Writeable}

object RRConfigsResponseReader extends Writeable.Reader[RRConfigsResponse] {
  override def read(in: StreamInput): RRConfigsResponse = {
    val response = new RRConfigsResponse
    response.readNodesFrom(in)
    response
  }
}
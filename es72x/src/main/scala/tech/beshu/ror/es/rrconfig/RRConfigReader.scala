package tech.beshu.ror.es.rrconfig

import org.elasticsearch.common.io.stream.{StreamInput, Writeable}

object RRConfigReader extends Writeable.Reader[RRConfig] {
  override def read(in: StreamInput): RRConfig = {
    val response = new RRConfig
    response.readFrom(in)
    response
  }
}

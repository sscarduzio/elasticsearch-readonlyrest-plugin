package tech.beshu.ror.es.rrconfig

import org.elasticsearch.action.ActionType
import org.elasticsearch.common.io.stream.Writeable

class RRConfigAction extends ActionType[RRConfigsResponse](RRConfigAction.name, RRConfigAction.reader)

object RRConfigAction {
  val name = "cluster:admin/rrconfig/config"
  val instance = new RRConfigAction
  val reader: Writeable.Reader[RRConfigsResponse] = new RRConfigsResponse(_)
}

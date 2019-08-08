package tech.beshu.ror.es.rradmin

import org.elasticsearch.action.ActionType

class RRAdminAction extends ActionType[RRAdminResponse](RRAdminAction.name)
object RRAdminAction {
  val name = "cluster:admin/rradmin/refreshsettings"
  val instance = new RRAdminAction()
}
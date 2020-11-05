package tech.beshu.ror.es.actions.rrauditevent

import org.elasticsearch.action.ActionType
import org.elasticsearch.common.io.stream.Writeable

class RRAuditEventActionType extends ActionType[RRAuditEventResponse](
  RRAuditEventActionType.name, RRAuditEventActionType.exceptionReader
)

object RRAuditEventActionType {
  val name = "cluster:ror/audit_event/put"
  val instance = new RRAuditEventActionType()

  final case object RRAuditEventActionTypeBeTransported extends Exception

  private [rrauditevent] def exceptionReader[A]: Writeable.Reader[A] = _ => throw RRAuditEventActionTypeBeTransported
}
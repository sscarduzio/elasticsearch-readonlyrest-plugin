package tech.beshu.ror.es.actions.rrmetadata

import org.elasticsearch.action.ActionType
import org.elasticsearch.common.io.stream.Writeable

class RRUserMetadataActionType extends ActionType[RRUserMetadataResponse](
  RRUserMetadataActionType.name, RRUserMetadataActionType.exceptionReader
)

object RRUserMetadataActionType {
  val name = "cluster:ror/user_metadata/get"
  val instance = new RRUserMetadataActionType()

  final case object RRUserMetadataActionCannotBeTransported extends Exception

  private [rrmetadata] def exceptionReader[A]: Writeable.Reader[A] = _ => throw RRUserMetadataActionCannotBeTransported
}
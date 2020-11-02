package tech.beshu.ror.es.actions.rrmetadata

import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.support.{ActionFilters, HandledTransportAction}
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.tasks.Task
import org.elasticsearch.transport.TransportService

class TransportRRUserMetadataAction(transportService: TransportService,
                                    actionFilters: ActionFilters,
                                    constructorDiscriminator: Unit)
  extends HandledTransportAction[RRUserMetadataRequest, RRUserMetadataResponse](
    RRUserMetadataActionType.name, transportService, actionFilters, RRUserMetadataActionType.exceptionReader
  ) {

  @Inject
  def this(transportService: TransportService,
           actionFilters: ActionFilters) =
    this(transportService, actionFilters, ())

  override def doExecute(task: Task, request: RRUserMetadataRequest, listener: ActionListener[RRUserMetadataResponse]): Unit = {
    //execute(task, request, listener)
  }
}

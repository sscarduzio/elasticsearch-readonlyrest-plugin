package tech.beshu.ror.es.request

import cats.syntax.show._
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsToHeaderValue
import tech.beshu.ror.accesscontrol.request.RequestContext

object FLSContextHeaderHandler extends Logging {

  def addContextHeader(threadPool: ThreadPool,
                       fieldsRestrictions: FieldsRestrictions,
                       requestId: RequestContext.Id): Unit = {
    val threadContext = threadPool.getThreadContext
    val header = createContextHeader(fieldsRestrictions)
    logger.debug(s"[${requestId.show}] Adding thread context header required by lucene. Header Value: '${header.value.value}'")
    threadContext.putHeader(header.name.value.value, header.value.value)
  }

  private def createContextHeader(fieldsRestrictions: FieldsRestrictions) = {
    new Header(
      Name.transientFields,
      transientFieldsToHeaderValue.toRawValue(fieldsRestrictions)
    )
  }
}
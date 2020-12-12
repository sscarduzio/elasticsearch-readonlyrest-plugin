package tech.beshu.ror.accesscontrol.logging

import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.audit.instances.{DefaultAuditLogSerializer, QueryAuditLogSerializer}
import tech.beshu.ror.commons
import tech.beshu.ror.requestcontext.AuditLogSerializer

import scala.language.higherKinds
import com.github.ghik.silencer.silent

@silent("deprecated")
final class DeprecatedAuditLoggingDecorator[T](underlying: AuditLogSerializer[T])
  extends AuditLogSerializer[T]
    with Logging {
  private val deprecatedSerializerCanonicalName = underlying.getClass.getCanonicalName
  private val defaultSerializerCanonicalName = classOf[DefaultAuditLogSerializer].getCanonicalName
  private val querySerializerCanonicalName = classOf[QueryAuditLogSerializer].getCanonicalName


  override def createLoggableEntry(context: commons.ResponseContext): T = {
    logger.warn(s"you're using deprecated serializer $deprecatedSerializerCanonicalName, please use $defaultSerializerCanonicalName, or $querySerializerCanonicalName instead")
    underlying.createLoggableEntry(context)
  }
}
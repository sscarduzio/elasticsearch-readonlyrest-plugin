/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import javassist.{ClassPool, CtField, CtNewMethod, Modifier}
import org.apache.logging.log4j.scala.Logging

import scala.util.Try

object EsCode extends Logging {

  def improve(): Unit = {
    modifyEsRestNodeClient()
    modifyIndicesStatsResponse()
  }

  private def modifyEsRestNodeClient(): Unit = {
    Try {
      val esRestNodeClientClass = ClassPool.getDefault.get("org.elasticsearch.client.support.AbstractClient")

      val oldAdminMethod = esRestNodeClientClass.getDeclaredMethod("admin")

      if (esRestNodeClientClass.isFrozen) {
        esRestNodeClientClass.defrost()
      }

      esRestNodeClientClass.removeMethod(oldAdminMethod)

      val adminClientClass = ClassPool.getDefault.get("org.elasticsearch.client.AdminClient")
      val newAdminField = new CtField(adminClientClass, "rorAdmin", esRestNodeClientClass)
      esRestNodeClientClass.addField(newAdminField)

      val newAdminMethod = CtNewMethod.make(
        "public org.elasticsearch.client.AdminClient admin() { return this.rorAdmin; }",
        esRestNodeClientClass
      )
      esRestNodeClientClass.addMethod(newAdminMethod)

      esRestNodeClientClass.toClass
    }
      .fold(ex => this.logger.error("Es code modification error", ex), _ => ())
  }

  private def modifyIndicesStatsResponse(): Unit = {
    Try {
      val esRestNodeClientClass = ClassPool.getDefault.get("org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse")

      val constructors = esRestNodeClientClass.getConstructors
      constructors.foreach(c => c.setModifiers(Modifier.PUBLIC))

      esRestNodeClientClass.toClass
    }
      .fold(ex => this.logger.error("Es code modification error", ex), _ => ())
  }
}

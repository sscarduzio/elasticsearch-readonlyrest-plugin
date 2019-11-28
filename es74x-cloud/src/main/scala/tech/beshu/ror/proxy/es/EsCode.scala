package tech.beshu.ror.proxy.es

import javassist.{ClassPool, CtField, CtNewMethod, Modifier}

object EsCode {

  def improve(): Unit = {
    modifyEsRestNodeClient()
    modifyIndicesStatsResponse()
  }

  private def modifyEsRestNodeClient(): Unit = {
    val esRestNodeClientClass = ClassPool.getDefault.get("org.elasticsearch.client.support.AbstractClient")

    val oldAdminMethod = esRestNodeClientClass.getDeclaredMethod("admin")
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

  private def modifyIndicesStatsResponse(): Unit = {
    val esRestNodeClientClass = ClassPool.getDefault.get("org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse")

    val constructors = esRestNodeClientClass.getConstructors
    constructors.foreach(c => c.setModifiers(Modifier.PUBLIC))

    esRestNodeClientClass.toClass
  }
}

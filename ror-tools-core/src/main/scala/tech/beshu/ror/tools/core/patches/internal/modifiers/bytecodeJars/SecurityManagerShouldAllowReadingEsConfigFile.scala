/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars

import just.semver.SemVer
import org.objectweb.asm._
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier
import tech.beshu.ror.tools.core.utils.EsUtil.{es71722, es800}

import java.io.{File, InputStream}

private[patches] class SecurityManagerShouldAllowReadingEsConfigFile(esVersion: SemVer)
  extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/bootstrap/Security.class",
      processFileContent = removeForbiddingReadPermissionForElasticsearchYmlFile
    )
  }

  private def removeForbiddingReadPermissionForElasticsearchYmlFile(moduleInputStream: InputStream) = {
    val reader = new ClassReader(moduleInputStream)
    val writer = new ClassWriter(reader, 0)
    reader.accept(new EsClassVisitor(writer), 0)
    writer.toByteArray
  }

  private class EsClassVisitor(writer: ClassWriter)
    extends ClassVisitor(Opcodes.ASM9, writer) {

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      def noChanges = super.visitMethod(access, name, descriptor, signature, exceptions)

      name match {
        case _ if esVersion >= es800 =>
          noChanges
        case "createForbiddenFilePermissions" if esVersion >= es71722 =>
          new ElasticsearchYmlFileShouldBeReadable(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          noChanges
      }
    }
  }

  private class ElasticsearchYmlFileShouldBeReadable(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9, underlying) {

    private var modifyThePermissionList: Boolean = false

    override def visitLdcInsn(value: Any): Unit = {
      value match {
        case "elasticsearch.yml" =>
          modifyThePermissionList = true
          super.visitLdcInsn(value)
        case "read,readlink,write,delete,execute" if modifyThePermissionList =>
          modifyThePermissionList = false
          super.visitLdcInsn("write,delete,execute")
        case _ =>
          super.visitLdcInsn(value)
      }
    }
  }

}

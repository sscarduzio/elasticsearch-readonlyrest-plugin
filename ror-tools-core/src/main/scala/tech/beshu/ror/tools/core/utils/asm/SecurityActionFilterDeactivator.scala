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
package tech.beshu.ror.tools.core.utils.asm

import org.objectweb.asm._

import java.io.{File, InputStream}
import scala.language.postfixOps

object SecurityActionFilterDeactivator extends BytecodeJarModifier {

  def deactivateXpackSecurityFilter(jar: File): Unit = {
    val modifiedSecurityClass = loadAndProcessFileFromJar(
      jar = jar,
      classFileName = "org/elasticsearch/xpack/security/Security",
      processFileContent = doDeactivateXpackSecurityFilter
    )
    updateFileInJar(
      jar = jar,
      destinationPathSting = "/org/elasticsearch/xpack/security/Security.class",
      newContent = modifiedSecurityClass
    )
  }

  private def doDeactivateXpackSecurityFilter(moduleInputStream: InputStream) = {
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
      name match {
        case "getActionFilters" =>
          new EsMethodVisitor(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class EsMethodVisitor(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      underlying.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "emptyList",
        "()Ljava/util/List;",
        false
      )
      underlying.visitInsn(Opcodes.ARETURN)
      underlying.visitMaxs(1, 1)
      underlying.visitEnd()
    }
  }

  // todo: remove
  def main(args: Array[String]): Unit = {
    val jar = os.root / "tmp" / "x-pack-security-8.6.1.jar"
    val copiedJar = os.root / "tmp" / "x-pack-security-8.6.1-bak.jar"
    os.remove(copiedJar)
    os.copy(jar, copiedJar)
    SecurityActionFilterDeactivator.deactivateXpackSecurityFilter(copiedJar toIO)
  }
}

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

import org.objectweb.asm._
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.{File, InputStream}

private [patches] object DeactivateSecurityActionFilter extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/Security.class",
      processFileContent = doDeactivateXpackSecurityFilter
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
          new GetActionFiltersMethodReturningEmptyList(
            super.visitMethod(access, name, descriptor, signature, exceptions)
          )
        case "onIndexModule" =>
          // removing the onIndexModule method
          null
        case "getRequestCacheKeyDifferentiator" =>
          new GetRequestCacheKeyDifferentiatorReturningNull(
            super.visitMethod(access, name, descriptor, signature, exceptions)
          )
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class GetActionFiltersMethodReturningEmptyList(underlying: MethodVisitor)
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

  private class GetRequestCacheKeyDifferentiatorReturningNull(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      underlying.visitInsn(Opcodes.ACONST_NULL)
      underlying.visitInsn(Opcodes.ARETURN)
      underlying.visitMaxs(1, 1)
      underlying.visitEnd()
    }
  }

}

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
package tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.security

import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.{File, InputStream}

/**
 * Modifies the SecurityContext class to relax how authorization information is retrieved from
 * the thread context.
 *
 * This patch replaces `getAuthorizationInfoFromContext()` with a simplified implementation that
 * directly returns:
 *   (AuthorizationInfo) threadContext.getTransient("_authz_info")
 *
 * By removing the original null-check / fallback logic, the method no longer guards against a
 * missing `_authz_info` entry and simply returns whatever is present (or `null`). This helps
 * ReadonlyREST integrate with Elasticsearch’s authorization flow by relying on the thread-context
 * transient value without additional SecurityContext validation behavior.
 */
private[patches] object ModifySecurityContextClass
  extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/core/security/SecurityContext.class",
      processFileContent = doModifySecurityContextClass
    )
  }

  private def doModifySecurityContextClass(moduleInputStream: InputStream) = {
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
        case "getAuthorizationInfoFromContext" =>
          new GetAuthorizationInfoWithoutNullCheck(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class GetAuthorizationInfoWithoutNullCheck(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()

      // return (AuthorizationInfo) this.threadContext.getTransient("_authz_info");

      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/core/security/SecurityContext",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_authz_info")
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "getTransient",
        "(Ljava/lang/String;)Ljava/lang/Object;",
        false
      )
      underlying.visitTypeInsn(
        Opcodes.CHECKCAST,
        "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo"
      )
      underlying.visitInsn(Opcodes.ARETURN)

      underlying.visitMaxs(2, 1)
      underlying.visitEnd()
    }
  }
}

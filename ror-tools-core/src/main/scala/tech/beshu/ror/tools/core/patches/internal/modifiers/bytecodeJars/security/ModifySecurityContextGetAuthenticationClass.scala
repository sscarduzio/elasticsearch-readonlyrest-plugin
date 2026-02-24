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

import better.files.File
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.InputStream

/**
 * Modifies Authentication.getAuthentication(ThreadContext) in x-pack-core so that when the
 * `_xpack_security_authentication` transient is missing from ThreadContext (e.g. after a
 * doPrivileged thread switch), it falls back to Authentication.readFromContext(threadContext)
 * which deserializes the Authentication from the `_xpack_security_authentication` header.
 *
 * Without this patch, TransportHasPrivilegesAction (and other transport actions that call
 * Authentication.getAuthentication(threadContext)) get a NullPointerException when the transient is lost.
 *
 * Note: TransportHasPrivilegesAction.doExecute calls Authentication.getAuthentication(ThreadContext)
 * directly (a static method), NOT SecurityContext.getAuthentication(). The static getAuthentication
 * only reads the transient, while readFromContext checks both transient and header.
 */
private[patches] object ModifySecurityContextGetAuthenticationClass
  extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/core/security/authc/Authentication.class",
      processFileContent = doModifyAuthenticationClass
    )
  }

  private def doModifyAuthenticationClass(moduleInputStream: InputStream) = {
    val reader = new ClassReader(moduleInputStream)
    val writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
    reader.accept(new EsClassVisitor(writer), ClassReader.SKIP_FRAMES)
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
        case "getAuthentication" if descriptor == "(Lorg/elasticsearch/common/util/concurrent/ThreadContext;)Lorg/elasticsearch/xpack/core/security/authc/Authentication;" =>
          new GetAuthenticationWithHeaderFallback(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  /*
    Replaces static Authentication.getAuthentication(ThreadContext context) with:

      try {
        return Authentication.readFromContext(context);
      } catch (Exception e) {
        return null;
      }

    readFromContext already checks the transient first, then falls back to deserializing
    the header. This is strictly more capable than the original getAuthentication which
    only reads the transient.
  */
  private class GetAuthenticationWithHeaderFallback(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()

      val catchLabel = new Label()
      val tryStartLabel = new Label()

      // try-catch block covering the readFromContext call
      underlying.visitTryCatchBlock(tryStartLabel, catchLabel, catchLabel, "java/lang/Exception")

      // try { return Authentication.readFromContext(context); }
      underlying.visitLabel(tryStartLabel)
      underlying.visitVarInsn(Opcodes.ALOAD, 0) // static method: arg0 = context
      underlying.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/xpack/core/security/authc/Authentication",
        "readFromContext",
        "(Lorg/elasticsearch/common/util/concurrent/ThreadContext;)Lorg/elasticsearch/xpack/core/security/authc/Authentication;",
        false
      )
      underlying.visitInsn(Opcodes.ARETURN)

      // catch (Exception e) { return null; }
      underlying.visitLabel(catchLabel)
      underlying.visitVarInsn(Opcodes.ASTORE, 1) // store exception
      underlying.visitInsn(Opcodes.ACONST_NULL)
      underlying.visitInsn(Opcodes.ARETURN)

      underlying.visitMaxs(2, 2)
      underlying.visitEnd()
    }
  }
}

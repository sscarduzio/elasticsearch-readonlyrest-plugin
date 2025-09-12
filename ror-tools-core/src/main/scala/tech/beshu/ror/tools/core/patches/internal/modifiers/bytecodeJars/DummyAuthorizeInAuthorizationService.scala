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

import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.{File, InputStream}

private [patches] object DummyAuthorizeInAuthorizationService extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/authz/AuthorizationService.class",
      processFileContent = doMakeAuthorizeMethodDummy
    )
  }

  private def doMakeAuthorizeMethodDummy(moduleInputStream: InputStream) = {
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
        case "authorize" =>
          new DummyAuthorizationMethod(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  /**
   * Method visitor that replaces the authorize method implementation with a dummy version.
   * The dummy implementation:
   * 1. Checks if "_indices_permissions" is already set in the thread context
   * 2. If not set, grants full access by calling IndicesAccessControl.allowAll()
   * 3. Always calls the listener with a null response to indicate successful authorization
   * 
   * This effectively bypasses Elasticsearch's authorization checks for ReadonlyREST integration.
   */
  private class DummyAuthorizationMethod(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      underlying.visitLabel(label0)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authz/AuthorizationService", "threadContext", "Lorg/elasticsearch/common/util/concurrent/ThreadContext;")
      underlying.visitLdcInsn("_indices_permissions")
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "getTransient", "(Ljava/lang/String;)Ljava/lang/Object;", false)
      val label1 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNONNULL, label1)
      val label2 = new Label()
      underlying.visitLabel(label2)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authz/AuthorizationService", "securityContext", "Lorg/elasticsearch/xpack/core/security/SecurityContext;")
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/core/security/authz/accesscontrol/IndicesAccessControl", "allowAll", "()Lorg/elasticsearch/xpack/core/security/authz/accesscontrol/IndicesAccessControl;", false)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/core/security/SecurityContext", "putIndicesAccessControl", "(Lorg/elasticsearch/xpack/core/security/authz/accesscontrol/IndicesAccessControl;)V", false)
      underlying.visitLabel(label1)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitInsn(Opcodes.ACONST_NULL)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/action/ActionListener", "onResponse", "(Ljava/lang/Object;)V", true)
      val label3 = new Label()
      underlying.visitLabel(label3)
      underlying.visitInsn(Opcodes.RETURN)
      val label4 = new Label()
      underlying.visitLabel(label4)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;", null, label0, label4, 0)
      underlying.visitLocalVariable("authentication", "Lorg/elasticsearch/xpack/core/security/authc/Authentication;", null, label0, label4, 1)
      underlying.visitLocalVariable("action", "Ljava/lang/String;", null, label0, label4, 2)
      underlying.visitLocalVariable("originalRequest", "Lorg/elasticsearch/transport/TransportRequest;", null, label0, label4, 3)
      underlying.visitLocalVariable("listener", "Lorg/elasticsearch/action/ActionListener;", "Lorg/elasticsearch/action/ActionListener<Ljava/lang/Void;>;", label0, label4, 4)
      underlying.visitMaxs(2, 5)
      underlying.visitEnd()
    }
  }
}

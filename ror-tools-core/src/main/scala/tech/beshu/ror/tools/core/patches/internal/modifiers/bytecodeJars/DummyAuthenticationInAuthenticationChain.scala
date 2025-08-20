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

private[patches] object DummyAuthenticationInAuthenticationChain extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/authc/AuthenticatorChain.class",
      processFileContent = putDummyAuthenticationInAuthenticationChain
    )
  }

  private def putDummyAuthenticationInAuthenticationChain(moduleInputStream: InputStream) = {
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
        case "doAuthenticate" =>
          new TodoChangeMe(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }

    // todo: change name
    private class TodoChangeMe(underlying: MethodVisitor)
      extends MethodVisitor(Opcodes.ASM9) {

      override def visitCode(): Unit = {
        underlying.visitCode()
        val label0 = new Label()
        underlying.visitLabel(label0)
        underlying.visitLineNumber(102, label0)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/Authenticator$Context", "getThreadContext", "()Lorg/elasticsearch/common/util/concurrent/ThreadContext;", false)
        underlying.visitLdcInsn("X-elastic-product")
        underlying.visitLdcInsn("Elasticsearch")
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "addResponseHeader", "(Ljava/lang/String;Ljava/lang/String;)V", false)
        val label1 = new Label()
        underlying.visitLabel(label1)
        underlying.visitLineNumber(106, label1)
        underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/user/InternalUsers", "XPACK_USER", "Lorg/elasticsearch/xpack/core/security/user/InternalUser;")
        val label2 = new Label()
        underlying.visitLabel(label2)
        underlying.visitLineNumber(108, label2)
        underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/TransportVersion", "current", "()Lorg/elasticsearch/TransportVersion;", false)
        underlying.visitLdcInsn("any")
        val label3 = new Label()
        underlying.visitLabel(label3)
        underlying.visitLineNumber(106, label3)
        underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/core/security/authc/Authentication", "newInternalAuthentication", "(Lorg/elasticsearch/xpack/core/security/user/InternalUser;Lorg/elasticsearch/TransportVersion;Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authc/Authentication;", false)
        underlying.visitVarInsn(Opcodes.ASTORE, 3)
        val label4 = new Label()
        underlying.visitLabel(label4)
        underlying.visitLineNumber(113, label4)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitVarInsn(Opcodes.ALOAD, 3)
        underlying.visitVarInsn(Opcodes.ALOAD, 2)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/AuthenticatorChain", "maybeLookupRunAsUser", "(Lorg/elasticsearch/xpack/security/authc/Authenticator$Context;Lorg/elasticsearch/xpack/core/security/authc/Authentication;Lorg/elasticsearch/action/ActionListener;)V", false)
        val label5 = new Label()
        underlying.visitLabel(label5)
        underlying.visitLineNumber(114, label5)
        underlying.visitInsn(Opcodes.RETURN)
        val label6 = new Label()
        underlying.visitLabel(label6)
        underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authc/AuthenticatorChain;", null, label0, label6, 0)
        underlying.visitLocalVariable("context", "Lorg/elasticsearch/xpack/security/authc/Authenticator$Context;", null, label0, label6, 1)
        underlying.visitLocalVariable("listener", "Lorg/elasticsearch/action/ActionListener;", "Lorg/elasticsearch/action/ActionListener<Lorg/elasticsearch/xpack/core/security/authc/Authentication;>;", label0, label6, 2)
        underlying.visitLocalVariable("auth", "Lorg/elasticsearch/xpack/core/security/authc/Authentication;", null, label4, label6, 3)
        underlying.visitMaxs(4, 4)
        underlying.visitEnd()
      }
    }
  }
}

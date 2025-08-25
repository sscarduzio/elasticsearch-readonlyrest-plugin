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
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier
import tech.beshu.ror.tools.core.utils.EsUtil.{es7160, es770}

import java.io.{File, InputStream}

private[patches] class DummyAuthenticationInAuthenticationServiceAuthenticator(esVersion: SemVer) extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator.class",
      processFileContent = putDummyAuthenticationInAuthenticator
    )
  }

  private def putDummyAuthenticationInAuthenticator(moduleInputStream: InputStream) = {
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
      esVersion match {
        case v if v >= es7160 =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
        case v if v >= es770 =>
          name match {
            case "authenticateAsync" =>
              new SetXpackUserAsAuthenticatedUserInAuthenticateAsyncMethod(super.visitMethod(access, name, descriptor, signature, exceptions))
            case "consumeToken" =>
              new SetXpackUserAsAuthenticatedUserInConsumeToken(super.visitMethod(access, name, descriptor, signature, exceptions))
            case _ =>
              super.visitMethod(access, name, descriptor, signature, exceptions)
          }
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }

    private class SetXpackUserAsAuthenticatedUserInAuthenticateAsyncMethod(underlying: MethodVisitor)
      extends MethodVisitor(Opcodes.ASM9) {

      override def visitCode(): Unit = {
        underlying.visitCode()
        val label0 = new Label()
        underlying.visitLabel(label0)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "request", "Lorg/elasticsearch/xpack/security/authc/AuthenticationService$AuditableRequest;")
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$AuditableRequest", "threadContext", "Lorg/elasticsearch/common/util/concurrent/ThreadContext;")
        underlying.visitLdcInsn("X-elastic-product")
        underlying.visitLdcInsn("Elasticsearch")
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "addResponseHeader", "(Ljava/lang/String;Ljava/lang/String;)V", false)
        val label1 = new Label()
        underlying.visitLabel(label1)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authc/Authentication$RealmRef")
        underlying.visitInsn(Opcodes.DUP)
        underlying.visitLdcInsn("__attach")
        underlying.visitLdcInsn("__attach")
        underlying.visitLdcInsn("any")
        underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/core/security/authc/Authentication$RealmRef", "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false)
        underlying.visitFieldInsn(Opcodes.PUTFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "authenticatedBy", "Lorg/elasticsearch/xpack/core/security/authc/Authentication$RealmRef;")
        val label2 = new Label()
        underlying.visitLabel(label2)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/user/XPackUser", "INSTANCE", "Lorg/elasticsearch/xpack/core/security/user/XPackUser;")
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "finishAuthentication", "(Lorg/elasticsearch/xpack/core/security/user/User;)V", false)
        val label3 = new Label()
        underlying.visitLabel(label3)
        underlying.visitInsn(Opcodes.RETURN)
        val label4 = new Label()
        underlying.visitLabel(label4)
        underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator;", null, label0, label4, 0)
        underlying.visitMaxs(6, 1)
        underlying.visitEnd()
      }
    }

    private class SetXpackUserAsAuthenticatedUserInConsumeToken(underlying: MethodVisitor)
      extends MethodVisitor(Opcodes.ASM9) {

      override def visitCode(): Unit = {
        underlying.visitCode()
        val label0 = new Label()
        underlying.visitLabel(label0)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "request", "Lorg/elasticsearch/xpack/security/authc/AuthenticationService$AuditableRequest;")
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$AuditableRequest", "threadContext", "Lorg/elasticsearch/common/util/concurrent/ThreadContext;")
        underlying.visitLdcInsn("X-elastic-product")
        underlying.visitLdcInsn("Elasticsearch")
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "addResponseHeader", "(Ljava/lang/String;Ljava/lang/String;)V", false)
        val label1 = new Label()
        underlying.visitLabel(label1)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authc/Authentication$RealmRef")
        underlying.visitInsn(Opcodes.DUP)
        underlying.visitLdcInsn("__attach")
        underlying.visitLdcInsn("__attach")
        underlying.visitLdcInsn("any")
        underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/core/security/authc/Authentication$RealmRef", "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false)
        underlying.visitFieldInsn(Opcodes.PUTFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "authenticatedBy", "Lorg/elasticsearch/xpack/core/security/authc/Authentication$RealmRef;")
        val label2 = new Label()
        underlying.visitLabel(label2)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/user/XPackUser", "INSTANCE", "Lorg/elasticsearch/xpack/core/security/user/XPackUser;")
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "finishAuthentication", "(Lorg/elasticsearch/xpack/core/security/user/User;)V", false)
        val label3 = new Label()
        underlying.visitLabel(label3)
        underlying.visitInsn(Opcodes.RETURN)
        val label4 = new Label()
        underlying.visitLabel(label4)
        underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator;", null, label0, label4, 0)
        underlying.visitLocalVariable("token", "Lorg/elasticsearch/xpack/core/security/authc/AuthenticationToken;", null, label0, label4, 1)
        underlying.visitMaxs(6, 2)
        underlying.visitEnd()
      }
    }
  }
}

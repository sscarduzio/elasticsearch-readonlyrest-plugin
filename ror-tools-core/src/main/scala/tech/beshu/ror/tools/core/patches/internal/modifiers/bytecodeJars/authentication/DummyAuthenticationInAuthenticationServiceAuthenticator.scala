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
package tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.authentication

import just.semver.SemVer
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier
import tech.beshu.ror.tools.core.utils.EsUtil.{es670, es7150, es7160}

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

    override def visit(version: Int, access: Int, name: String, signature: String, superName: String, interfaces: Array[String]): Unit = {
      super.visit(version, access, name, signature, superName, interfaces)
      esVersion match {
        case v if v >= es7150 => // nothing
        case v if v >= es670 => RorAuthenticateXPackUserMethod.create(this)
        case _ => // nothing
      }
    }

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      name match {
        case "authenticateAsync" =>
          esVersion match {
            case v if v >= es7160 =>
              super.visitMethod(access, name, descriptor, signature, exceptions)
            case v if v >= es7150 =>
              new SetXpackUserAsAuthenticatedUserInAuthenticateAsyncMethodForEsGreaterOrEqual7150(super.visitMethod(access, name, descriptor, signature, exceptions))
            case v if v >= es670 =>
              new SetXpackUserAsAuthenticatedUserInAuthenticateAsyncMethodForEsGreaterOrEqual670(super.visitMethod(access, name, descriptor, signature, exceptions))
            case _ =>
              super.visitMethod(access, name, descriptor, signature, exceptions)
          }
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }

    private object RorAuthenticateXPackUserMethod {

      def create(classVisitor: ClassVisitor): Unit = {
        val methodVisitor = classVisitor.visitMethod(Opcodes.ACC_PRIVATE, "rorAuthenticateXPackUser", "(Lorg/elasticsearch/xpack/core/security/authc/Authentication;)V", null, null)
        methodVisitor.visitCode()
        val label0 = new Label()
        methodVisitor.visitLabel(label0)
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
        val label1 = new Label()
        methodVisitor.visitJumpInsn(Opcodes.IFNULL, label1)
        val label2 = new Label()
        methodVisitor.visitLabel(label2)
        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/security/authc/AuthenticationService", "access$600", "()Lorg/apache/logging/log4j/Logger;", false)
        methodVisitor.visitLdcInsn("Found existing authentication [{}] in request [{}]")
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "request", "Lorg/elasticsearch/xpack/security/authc/AuthenticationService$AuditableRequest;")
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/apache/logging/log4j/Logger", "trace", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", true)
        val label3 = new Label()
        methodVisitor.visitLabel(label3)
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "listener", "Lorg/elasticsearch/action/ActionListener;")
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/action/ActionListener", "onResponse", "(Ljava/lang/Object;)V", true)
        val label4 = new Label()
        methodVisitor.visitJumpInsn(Opcodes.GOTO, label4)
        methodVisitor.visitLabel(label1)
        methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "request", "Lorg/elasticsearch/xpack/security/authc/AuthenticationService$AuditableRequest;")
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$AuditableRequest", "threadContext", "Lorg/elasticsearch/common/util/concurrent/ThreadContext;")
        methodVisitor.visitLdcInsn("X-elastic-product")
        methodVisitor.visitLdcInsn("Elasticsearch")
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "addResponseHeader", "(Ljava/lang/String;Ljava/lang/String;)V", false)
        val label5 = new Label()
        methodVisitor.visitLabel(label5)
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        methodVisitor.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authc/Authentication$RealmRef")
        methodVisitor.visitInsn(Opcodes.DUP)
        methodVisitor.visitLdcInsn("__attach")
        methodVisitor.visitLdcInsn("__attach")
        methodVisitor.visitLdcInsn("any")
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/core/security/authc/Authentication$RealmRef", "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false)
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "authenticatedBy", "Lorg/elasticsearch/xpack/core/security/authc/Authentication$RealmRef;")
        val label6 = new Label()
        methodVisitor.visitLabel(label6)
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/user/XPackUser", "INSTANCE", "Lorg/elasticsearch/xpack/core/security/user/XPackUser;")
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "finishAuthentication", "(Lorg/elasticsearch/xpack/core/security/user/User;)V", false)
        methodVisitor.visitLabel(label4)
        methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        methodVisitor.visitInsn(Opcodes.RETURN)
        val label7 = new Label()
        methodVisitor.visitLabel(label7)
        methodVisitor.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator;", null, label0, label7, 0)
        methodVisitor.visitLocalVariable("authentication", "Lorg/elasticsearch/xpack/core/security/authc/Authentication;", null, label0, label7, 1)
        methodVisitor.visitMaxs(6, 2)
        methodVisitor.visitEnd()
      }
    }

    private class SetXpackUserAsAuthenticatedUserInAuthenticateAsyncMethodForEsGreaterOrEqual7150(underlying: MethodVisitor)
      extends MethodVisitor(Opcodes.ASM9) {

      override def visitCode(): Unit = {
        underlying.visitCode()
        val label0 = new Label()
        val label1 = new Label()
        val label2 = new Label()
        underlying.visitTryCatchBlock(label0, label1, label2, "java/lang/Exception")
        val label3 = new Label()
        underlying.visitLabel(label3)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "defaultOrderedRealmList", "Ljava/util/List;")
        underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true)
        underlying.visitJumpInsn(Opcodes.IFEQ, label0)
        val label4 = new Label()
        underlying.visitLabel(label4)
        underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/security/authc/AuthenticationService", "access$600", "()Lorg/apache/logging/log4j/Logger;", false)
        underlying.visitLdcInsn("No realms available, failing authentication")
        underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/apache/logging/log4j/Logger", "debug", "(Ljava/lang/String;)V", true)
        val label5 = new Label()
        underlying.visitLabel(label5)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "listener", "Lorg/elasticsearch/action/ActionListener;")
        underlying.visitInsn(Opcodes.ACONST_NULL)
        underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/action/ActionListener", "onResponse", "(Ljava/lang/Object;)V", true)
        val label6 = new Label()
        underlying.visitJumpInsn(Opcodes.GOTO, label6)
        underlying.visitLabel(label0)
        underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "lookForExistingAuthentication", "()Lorg/elasticsearch/xpack/core/security/authc/Authentication;", false)
        underlying.visitVarInsn(Opcodes.ASTORE, 1)
        underlying.visitLabel(label1)
        val label7 = new Label()
        underlying.visitJumpInsn(Opcodes.GOTO, label7)
        underlying.visitLabel(label2)
        underlying.visitFrame(Opcodes.F_SAME1, 0, null, 1, Array("java/lang/Exception"))
        underlying.visitVarInsn(Opcodes.ASTORE, 2)
        val label8 = new Label()
        underlying.visitLabel(label8)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "listener", "Lorg/elasticsearch/action/ActionListener;")
        underlying.visitVarInsn(Opcodes.ALOAD, 2)
        underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/action/ActionListener", "onFailure", "(Ljava/lang/Exception;)V", true)
        val label9 = new Label()
        underlying.visitLabel(label9)
        underlying.visitInsn(Opcodes.RETURN)
        underlying.visitLabel(label7)
        underlying.visitFrame(Opcodes.F_APPEND, 1, Array("org/elasticsearch/xpack/core/security/authc/Authentication"), 0, null)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        val label10 = new Label()
        underlying.visitJumpInsn(Opcodes.IFNULL, label10)
        val label11 = new Label()
        underlying.visitLabel(label11)
        underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/security/authc/AuthenticationService", "access$600", "()Lorg/apache/logging/log4j/Logger;", false)
        underlying.visitLdcInsn("Found existing authentication [{}] in request [{}]")
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "request", "Lorg/elasticsearch/xpack/security/authc/AuthenticationService$AuditableRequest;")
        underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/apache/logging/log4j/Logger", "trace", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", true)
        val label12 = new Label()
        underlying.visitLabel(label12)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "listener", "Lorg/elasticsearch/action/ActionListener;")
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/action/ActionListener", "onResponse", "(Ljava/lang/Object;)V", true)
        underlying.visitJumpInsn(Opcodes.GOTO, label6)
        underlying.visitLabel(label10)
        underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "request", "Lorg/elasticsearch/xpack/security/authc/AuthenticationService$AuditableRequest;")
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$AuditableRequest", "threadContext", "Lorg/elasticsearch/common/util/concurrent/ThreadContext;")
        underlying.visitLdcInsn("X-elastic-product")
        underlying.visitLdcInsn("Elasticsearch")
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "addResponseHeader", "(Ljava/lang/String;Ljava/lang/String;)V", false)
        val label13 = new Label()
        underlying.visitLabel(label13)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authc/Authentication$RealmRef")
        underlying.visitInsn(Opcodes.DUP)
        underlying.visitLdcInsn("__attach")
        underlying.visitLdcInsn("__attach")
        underlying.visitLdcInsn("any")
        underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/core/security/authc/Authentication$RealmRef", "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false)
        underlying.visitFieldInsn(Opcodes.PUTFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "authenticatedBy", "Lorg/elasticsearch/xpack/core/security/authc/Authentication$RealmRef;")
        val label14 = new Label()
        underlying.visitLabel(label14)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/user/XPackUser", "INSTANCE", "Lorg/elasticsearch/xpack/core/security/user/XPackUser;")
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "finishAuthentication", "(Lorg/elasticsearch/xpack/core/security/user/User;)V", false)
        underlying.visitLabel(label6)
        underlying.visitFrame(Opcodes.F_CHOP, 1, null, 0, null)
        underlying.visitInsn(Opcodes.RETURN)
        val label15 = new Label()
        underlying.visitLabel(label15)
        underlying.visitLocalVariable("authentication", "Lorg/elasticsearch/xpack/core/security/authc/Authentication;", null, label1, label2, 1)
        underlying.visitLocalVariable("e", "Ljava/lang/Exception;", null, label8, label7, 2)
        underlying.visitLocalVariable("authentication", "Lorg/elasticsearch/xpack/core/security/authc/Authentication;", null, label7, label6, 1)
        underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator;", null, label3, label15, 0)
        underlying.visitMaxs(6, 3)
        underlying.visitEnd()
      }
    }

    private class SetXpackUserAsAuthenticatedUserInAuthenticateAsyncMethodForEsGreaterOrEqual670(underlying: MethodVisitor)
      extends MethodVisitor(Opcodes.ASM9) {

      override def visitCode(): Unit = {
        underlying.visitCode()
        val label0 = new Label()
        underlying.visitLabel(label0)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "defaultOrderedRealmList", "Ljava/util/List;")
        underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true)
        val label1 = new Label()
        underlying.visitJumpInsn(Opcodes.IFEQ, label1)
        val label2 = new Label()
        underlying.visitLabel(label2)
        underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/security/authc/AuthenticationService", "access$600", "()Lorg/apache/logging/log4j/Logger;", false)
        underlying.visitLdcInsn("No realms available, failing authentication")
        underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/apache/logging/log4j/Logger", "debug", "(Ljava/lang/String;)V", true)
        val label3 = new Label()
        underlying.visitLabel(label3)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "listener", "Lorg/elasticsearch/action/ActionListener;")
        underlying.visitInsn(Opcodes.ACONST_NULL)
        underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/action/ActionListener", "onResponse", "(Ljava/lang/Object;)V", true)
        val label4 = new Label()
        underlying.visitJumpInsn(Opcodes.GOTO, label4)
        underlying.visitLabel(label1)
        underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitInvokeDynamicInsn(
          "accept",
          "(Lorg/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator;)Ljava/util/function/Consumer;",
          new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
          Array[Object](
            Type.getType("(Ljava/lang/Object;)V"),
            new Handle(Opcodes.H_INVOKESPECIAL, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "rorAuthenticateXPackUser", "(Lorg/elasticsearch/xpack/core/security/authc/Authentication;)V", false),
            Type.getType("(Lorg/elasticsearch/xpack/core/security/authc/Authentication;)V")
          ): _*
        )
        underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator", "lookForExistingAuthentication", "(Ljava/util/function/Consumer;)V", false)
        underlying.visitLabel(label4)
        underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        underlying.visitInsn(Opcodes.RETURN)
        val label5 = new Label()
        underlying.visitLabel(label5)
        underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authc/AuthenticationService$Authenticator;", null, label0, label5, 0)
        underlying.visitMaxs(2, 1)
        underlying.visitEnd()
      }
    }
  }
}

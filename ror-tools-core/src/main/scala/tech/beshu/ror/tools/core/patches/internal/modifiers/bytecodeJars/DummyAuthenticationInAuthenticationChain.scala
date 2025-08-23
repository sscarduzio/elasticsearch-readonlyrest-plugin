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
import tech.beshu.ror.tools.core.utils.EsUtil.*

import java.io.{File, InputStream}

private[patches] class DummyAuthenticationInAuthenticationChain(esVersion: SemVer) extends BytecodeJarModifier {

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
          esVersion match {
            case v if v >= es8120 =>
              new SetXpackUserAsAuthenticatedUserForEsGreaterOrEqual8120(super.visitMethod(access, name, descriptor, signature, exceptions))
            case v if v >= es890 =>
              new SetXpackUserAsAuthenticatedUserForEsGreaterOrEqual890x(super.visitMethod(access, name, descriptor, signature, exceptions))
            case v if v >= es870 =>
              new SetXpackUserAsAuthenticatedUserForEsGreaterOrEqual870(super.visitMethod(access, name, descriptor, signature, exceptions))
            case v if v >= es820 =>
              new SetXpackUserAsAuthenticatedUserForEsGreaterOrEqual820(super.visitMethod(access, name, descriptor, signature, exceptions))
            case v if v >= es71713 =>
              new SetXpackUserAsAuthenticatedUserForEsGreaterOrEqual71713(super.visitMethod(access, name, descriptor, signature, exceptions))
            case v if v >= es7160 =>
              new SetXpackUserAsAuthenticatedUserForEsGreaterOrEqual71713(super.visitMethod(access, name, descriptor, signature, exceptions))
            case _ =>
              super.visitMethod(access, name, descriptor, signature, exceptions)
          }
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }

    private class SetXpackUserAsAuthenticatedUserForEsGreaterOrEqual8120(underlying: MethodVisitor)
      extends MethodVisitor(Opcodes.ASM9) {

      override def visitCode(): Unit = {
        underlying.visitCode()
        val label0 = new Label()
        underlying.visitLabel(label0)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/Authenticator$Context", "getThreadContext", "()Lorg/elasticsearch/common/util/concurrent/ThreadContext;", false)
        underlying.visitLdcInsn("X-elastic-product")
        underlying.visitLdcInsn("Elasticsearch")
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "addResponseHeader", "(Ljava/lang/String;Ljava/lang/String;)V", false)
        val label1 = new Label()
        underlying.visitLabel(label1)
        underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/user/InternalUsers", "XPACK_USER", "Lorg/elasticsearch/xpack/core/security/user/InternalUser;")
        val label2 = new Label()
        underlying.visitLabel(label2)
        underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/TransportVersion", "current", "()Lorg/elasticsearch/TransportVersion;", false)
        underlying.visitLdcInsn("any")
        val label3 = new Label()
        underlying.visitLabel(label3)
        underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/core/security/authc/Authentication", "newInternalAuthentication", "(Lorg/elasticsearch/xpack/core/security/user/InternalUser;Lorg/elasticsearch/TransportVersion;Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authc/Authentication;", false)
        underlying.visitVarInsn(Opcodes.ASTORE, 3)
        val label4 = new Label()
        underlying.visitLabel(label4)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitVarInsn(Opcodes.ALOAD, 3)
        underlying.visitVarInsn(Opcodes.ALOAD, 2)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/AuthenticatorChain", "maybeLookupRunAsUser", "(Lorg/elasticsearch/xpack/security/authc/Authenticator$Context;Lorg/elasticsearch/xpack/core/security/authc/Authentication;Lorg/elasticsearch/action/ActionListener;)V", false)
        val label5 = new Label()
        underlying.visitLabel(label5)
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

    private class SetXpackUserAsAuthenticatedUserForEsGreaterOrEqual890x(underlying: MethodVisitor)
      extends MethodVisitor(Opcodes.ASM9) {

      override def visitCode(): Unit = {
        underlying.visitCode()
        val label0 = new Label()
        underlying.visitLabel(label0)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/Authenticator$Context", "getThreadContext", "()Lorg/elasticsearch/common/util/concurrent/ThreadContext;", false)
        underlying.visitLdcInsn("X-elastic-product")
        underlying.visitLdcInsn("Elasticsearch")
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "addResponseHeader", "(Ljava/lang/String;Ljava/lang/String;)V", false)
        val label1 = new Label()
        underlying.visitLabel(label1)
        underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/user/InternalUsers", "XPACK_USER", "Lorg/elasticsearch/xpack/core/security/user/InternalUser;")
        underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/TransportVersion", "current", "()Lorg/elasticsearch/TransportVersion;", false)
        underlying.visitLdcInsn("any")
        underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/core/security/authc/Authentication", "newInternalAuthentication", "(Lorg/elasticsearch/xpack/core/security/user/InternalUser;Lorg/elasticsearch/TransportVersion;Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authc/Authentication;", false)
        underlying.visitVarInsn(Opcodes.ASTORE, 4)
        val label2 = new Label()
        underlying.visitLabel(label2)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitVarInsn(Opcodes.ALOAD, 4)
        underlying.visitVarInsn(Opcodes.ALOAD, 3)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/AuthenticatorChain", "maybeLookupRunAsUser", "(Lorg/elasticsearch/xpack/security/authc/Authenticator$Context;Lorg/elasticsearch/xpack/core/security/authc/Authentication;Lorg/elasticsearch/action/ActionListener;)V", false)
        val label3 = new Label()
        underlying.visitLabel(label3)
        underlying.visitInsn(Opcodes.RETURN)
        val label4 = new Label()
        underlying.visitLabel(label4)
        underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authc/AuthenticatorChain;", null, label0, label4, 0)
        underlying.visitLocalVariable("context", "Lorg/elasticsearch/xpack/security/authc/Authenticator$Context;", null, label0, label4, 1)
        underlying.visitLocalVariable("shouldExtractCredentials", "Z", null, label0, label4, 2)
        underlying.visitLocalVariable("listener", "Lorg/elasticsearch/action/ActionListener;", "Lorg/elasticsearch/action/ActionListener<Lorg/elasticsearch/xpack/core/security/authc/Authentication;>;", label0, label4, 3)
        underlying.visitLocalVariable("auth", "Lorg/elasticsearch/xpack/core/security/authc/Authentication;", null, label2, label4, 4)
        underlying.visitMaxs(4, 5)
        underlying.visitEnd()
      }
    }

    private class SetXpackUserAsAuthenticatedUserForEsGreaterOrEqual870(underlying: MethodVisitor)
      extends MethodVisitor(Opcodes.ASM9) {

      override def visitCode(): Unit = {
        underlying.visitCode()
        val label0 = new Label()
        underlying.visitLabel(label0)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/Authenticator$Context", "getThreadContext", "()Lorg/elasticsearch/common/util/concurrent/ThreadContext;", false)
        underlying.visitLdcInsn("X-elastic-product")
        underlying.visitLdcInsn("Elasticsearch")
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "addResponseHeader", "(Ljava/lang/String;Ljava/lang/String;)V", false)
        val label1 = new Label()
        underlying.visitLabel(label1)
        underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/user/XPackUser", "INSTANCE", "Lorg/elasticsearch/xpack/core/security/user/XPackUser;")
        underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/TransportVersion", "CURRENT", "Lorg/elasticsearch/TransportVersion;")
        underlying.visitLdcInsn("any")
        underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/core/security/authc/Authentication", "newInternalAuthentication", "(Lorg/elasticsearch/xpack/core/security/user/User;Lorg/elasticsearch/TransportVersion;Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authc/Authentication;", false)
        underlying.visitVarInsn(Opcodes.ASTORE, 4)
        val label2 = new Label()
        underlying.visitLabel(label2)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitVarInsn(Opcodes.ALOAD, 4)
        underlying.visitVarInsn(Opcodes.ALOAD, 3)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/AuthenticatorChain", "maybeLookupRunAsUser", "(Lorg/elasticsearch/xpack/security/authc/Authenticator$Context;Lorg/elasticsearch/xpack/core/security/authc/Authentication;Lorg/elasticsearch/action/ActionListener;)V", false)
        val label3 = new Label()
        underlying.visitLabel(label3)
        underlying.visitInsn(Opcodes.RETURN)
        val label4 = new Label()
        underlying.visitLabel(label4)
        underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authc/AuthenticatorChain;", null, label0, label4, 0)
        underlying.visitLocalVariable("context", "Lorg/elasticsearch/xpack/security/authc/Authenticator$Context;", null, label0, label4, 1)
        underlying.visitLocalVariable("shouldExtractCredentials", "Z", null, label0, label4, 2)
        underlying.visitLocalVariable("listener", "Lorg/elasticsearch/action/ActionListener;", "Lorg/elasticsearch/action/ActionListener<Lorg/elasticsearch/xpack/core/security/authc/Authentication;>;", label0, label4, 3)
        underlying.visitLocalVariable("auth", "Lorg/elasticsearch/xpack/core/security/authc/Authentication;", null, label2, label4, 4)
        underlying.visitMaxs(4, 5)
        underlying.visitEnd()
      }
    }

    private class SetXpackUserAsAuthenticatedUserForEsGreaterOrEqual820(underlying: MethodVisitor)
      extends MethodVisitor(Opcodes.ASM9) {

      override def visitCode(): Unit = {
        underlying.visitCode()
        val label0 = new Label()
        underlying.visitLabel(label0)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/Authenticator$Context", "getThreadContext", "()Lorg/elasticsearch/common/util/concurrent/ThreadContext;", false)
        underlying.visitLdcInsn("X-elastic-product")
        underlying.visitLdcInsn("Elasticsearch")
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "addResponseHeader", "(Ljava/lang/String;Ljava/lang/String;)V", false)
        val label1 = new Label()
        underlying.visitLabel(label1)
        underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/user/XPackUser", "INSTANCE", "Lorg/elasticsearch/xpack/core/security/user/XPackUser;")
        underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/Version", "CURRENT", "Lorg/elasticsearch/Version;")
        underlying.visitLdcInsn("any")
        underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/core/security/authc/Authentication", "newInternalAuthentication", "(Lorg/elasticsearch/xpack/core/security/user/User;Lorg/elasticsearch/Version;Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authc/Authentication;", false)
        underlying.visitVarInsn(Opcodes.ASTORE, 4)
        val label2 = new Label()
        underlying.visitLabel(label2)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitVarInsn(Opcodes.ALOAD, 4)
        underlying.visitVarInsn(Opcodes.ALOAD, 3)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/AuthenticatorChain", "maybeLookupRunAsUser", "(Lorg/elasticsearch/xpack/security/authc/Authenticator$Context;Lorg/elasticsearch/xpack/core/security/authc/Authentication;Lorg/elasticsearch/action/ActionListener;)V", false)
        val label3 = new Label()
        underlying.visitLabel(label3)
        underlying.visitInsn(Opcodes.RETURN)
        val label4 = new Label()
        underlying.visitLabel(label4)
        underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authc/AuthenticatorChain;", null, label0, label4, 0)
        underlying.visitLocalVariable("context", "Lorg/elasticsearch/xpack/security/authc/Authenticator$Context;", null, label0, label4, 1)
        underlying.visitLocalVariable("shouldExtractCredentials", "Z", null, label0, label4, 2)
        underlying.visitLocalVariable("listener", "Lorg/elasticsearch/action/ActionListener;", "Lorg/elasticsearch/action/ActionListener<Lorg/elasticsearch/xpack/core/security/authc/Authentication;>;", label0, label4, 3)
        underlying.visitLocalVariable("auth", "Lorg/elasticsearch/xpack/core/security/authc/Authentication;", null, label2, label4, 4)
        underlying.visitMaxs(4, 5)
        underlying.visitEnd()
      }
    }

    private class SetXpackUserAsAuthenticatedUserForEsGreaterOrEqual71713(underlying: MethodVisitor)
      extends MethodVisitor(Opcodes.ASM9) {

      override def visitCode(): Unit = {
        underlying.visitCode()
        val label0 = new Label()
        underlying.visitLabel(label0)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/Authenticator$Context", "getThreadContext", "()Lorg/elasticsearch/common/util/concurrent/ThreadContext;", false)
        underlying.visitLdcInsn("X-elastic-product")
        underlying.visitLdcInsn("Elasticsearch")
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "addResponseHeader", "(Ljava/lang/String;Ljava/lang/String;)V", false)
        val label1 = new Label()
        underlying.visitLabel(label1)
        underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authc/Authentication$RealmRef")
        underlying.visitInsn(Opcodes.DUP)
        underlying.visitLdcInsn("__attach")
        underlying.visitLdcInsn("__attach")
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authc/AuthenticatorChain", "nodeName", "Ljava/lang/String;")
        underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/core/security/authc/Authentication$RealmRef", "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false)
        underlying.visitVarInsn(Opcodes.ASTORE, 4)
        val label2 = new Label()
        underlying.visitLabel(label2)
        underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authc/Authentication")
        underlying.visitInsn(Opcodes.DUP)
        underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/user/XPackUser", "INSTANCE", "Lorg/elasticsearch/xpack/core/security/user/XPackUser;")
        underlying.visitVarInsn(Opcodes.ALOAD, 4)
        underlying.visitInsn(Opcodes.ACONST_NULL)
        underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/Version", "CURRENT", "Lorg/elasticsearch/Version;")
        underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/core/security/authc/Authentication", "<init>", "(Lorg/elasticsearch/xpack/core/security/user/User;Lorg/elasticsearch/xpack/core/security/authc/Authentication$RealmRef;Lorg/elasticsearch/xpack/core/security/authc/Authentication$RealmRef;Lorg/elasticsearch/Version;)V", false)
        underlying.visitVarInsn(Opcodes.ASTORE, 5)
        val label3 = new Label()
        underlying.visitLabel(label3)
        underlying.visitVarInsn(Opcodes.ALOAD, 0)
        underlying.visitVarInsn(Opcodes.ALOAD, 1)
        underlying.visitVarInsn(Opcodes.ALOAD, 5)
        underlying.visitVarInsn(Opcodes.ALOAD, 3)
        underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authc/AuthenticatorChain", "maybeLookupRunAsUser", "(Lorg/elasticsearch/xpack/security/authc/Authenticator$Context;Lorg/elasticsearch/xpack/core/security/authc/Authentication;Lorg/elasticsearch/action/ActionListener;)V", false)
        val label4 = new Label()
        underlying.visitLabel(label4)
        underlying.visitInsn(Opcodes.RETURN)
        val label5 = new Label()
        underlying.visitLabel(label5)
        underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authc/AuthenticatorChain;", null, label0, label5, 0)
        underlying.visitLocalVariable("context", "Lorg/elasticsearch/xpack/security/authc/Authenticator$Context;", null, label0, label5, 1)
        underlying.visitLocalVariable("shouldExtractCredentials", "Z", null, label0, label5, 2)
        underlying.visitLocalVariable("listener", "Lorg/elasticsearch/action/ActionListener;", "Lorg/elasticsearch/action/ActionListener<Lorg/elasticsearch/xpack/core/security/authc/Authentication;>;", label0, label5, 3)
        underlying.visitLocalVariable("authenticatedBy", "Lorg/elasticsearch/xpack/core/security/authc/Authentication$RealmRef;", null, label2, label5, 4)
        underlying.visitLocalVariable("auth", "Lorg/elasticsearch/xpack/core/security/authc/Authentication;", null, label3, label5, 5)
        underlying.visitMaxs(6, 6)
        underlying.visitEnd()
      }
    }

  }
}

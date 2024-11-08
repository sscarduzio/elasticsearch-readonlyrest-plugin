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

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.{File, InputStream}

private [patches] object DeactivateAuthenticationServiceInHttpTransport extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/Security.class",
      processFileContent = doDeactivateAuthenticationServiceInHttpTransport
    )
  }

  private def doDeactivateAuthenticationServiceInHttpTransport(moduleInputStream: InputStream) = {
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
        case name if isGetHttpTransportsMethodHttpValidatorLambda(name, descriptor) =>
          new GetHttpTransportsMethodHttpValidatorLambdaWithNoAuthenticationServiceUsage(
            super.visitMethod(access, name, descriptor, signature, exceptions)
          )
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class GetHttpTransportsMethodHttpValidatorLambdaWithNoAuthenticationServiceUsage(methodVisitor: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      methodVisitor.visitCode()

      methodVisitor.visitVarInsn(ALOAD, 4)
      methodVisitor.visitMethodInsn(
        INVOKESTATIC,
        "org/elasticsearch/http/netty4/internal/HttpHeadersAuthenticatorUtils",
        "asHttpPreRequest",
        "(Lio/netty/handler/codec/http/HttpRequest;)Lorg/elasticsearch/http/HttpPreRequest;",
        false
      )
      methodVisitor.visitVarInsn(ASTORE, 7)

      methodVisitor.visitVarInsn(ALOAD, 0)
      methodVisitor.visitVarInsn(ALOAD, 7)
      methodVisitor.visitVarInsn(ALOAD, 1)
      methodVisitor.visitMethodInsn(
        INVOKEINTERFACE,
        "java/util/function/BiConsumer",
        "accept",
        "(Ljava/lang/Object;Ljava/lang/Object;)V",
        true
      )

      methodVisitor.visitVarInsn(ALOAD, 2)
      methodVisitor.visitVarInsn(ALOAD, 5)
      methodVisitor.visitVarInsn(ALOAD, 1)
      methodVisitor.visitMethodInsn(
        INVOKEINTERFACE,
        "java/util/function/BiConsumer",
        "accept",
        "(Ljava/lang/Object;Ljava/lang/Object;)V",
        true
      )

      methodVisitor.visitVarInsn(ALOAD, 5)
      methodVisitor.visitVarInsn(ALOAD, 1)
      methodVisitor.visitMethodInsn(
        INVOKESTATIC,
        "org/elasticsearch/xpack/security/rest/RemoteHostHeader",
        "process",
        "(Lio/netty/channel/Channel;Lorg/elasticsearch/common/util/concurrent/ThreadContext;)V",
        false
      )

      // hack: just call anything on 'authenticationService' object
      methodVisitor.visitVarInsn(ALOAD, 3)
      methodVisitor.visitMethodInsn(
        INVOKEVIRTUAL,
        "java/lang/Object",
        "getClass",
        "()Ljava/lang/Class;",
        false
      )
      methodVisitor.visitInsn(POP)

      methodVisitor.visitVarInsn(ALOAD, 6)
      methodVisitor.visitInsn(ACONST_NULL)
      methodVisitor.visitMethodInsn(
        INVOKEINTERFACE,
        "org/elasticsearch/action/ActionListener",
        "onResponse",
        "(Ljava/lang/Object;)V",
        true
      )

      methodVisitor.visitInsn(RETURN)
      methodVisitor.visitMaxs(3, 8)

      methodVisitor.visitEnd()
    }
  }

  private def isGetHttpTransportsMethodHttpValidatorLambda(name: String, descriptor: String) = {
    name.contains("lambda$getHttpTransports$") &&
      descriptor == "(Ljava/util/function/BiConsumer;Lorg/elasticsearch/common/util/concurrent/ThreadContext;Ljava/util/function/BiConsumer;Lorg/elasticsearch/xpack/security/authc/AuthenticationService;Lio/netty/handler/codec/http/HttpRequest;Lio/netty/channel/Channel;Lorg/elasticsearch/action/ActionListener;)V"
  }
}

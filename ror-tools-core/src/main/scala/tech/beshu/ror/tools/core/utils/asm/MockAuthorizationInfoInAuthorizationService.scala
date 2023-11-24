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
import java.nio.file.Files

object MockAuthorizationInfoInAuthorizationService extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    val originalFileOwner = Files.getOwner(jar.toPath)
    val modifiedSecurityClass = loadAndProcessFileFromJar(
      jar = jar,
      classFileName = "org/elasticsearch/xpack/security/authz/AuthorizationService",
      processFileContent = doMockAuthorizationInfo
    )
    updateFileInJar(
      jar = jar,
      destinationPathSting = "org/elasticsearch/xpack/security/authz/AuthorizationService.class",
      newContent = modifiedSecurityClass
    )
    Files.setOwner(jar.toPath, originalFileOwner)
  }

  private def doMockAuthorizationInfo(moduleInputStream: InputStream) = {
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
          new GetAuthorizationInfoFromContextReturingMockAuthorizationInfo(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class GetAuthorizationInfoFromContextReturingMockAuthorizationInfo(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      underlying.visitLabel(label0)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authz/AuthorizationService", "threadContext", "Lorg/elasticsearch/common/util/concurrent/ThreadContext;")
      underlying.visitLdcInsn("_authz_info")
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "getTransient", "(Ljava/lang/String;)Ljava/lang/Object;", false)
      underlying.visitTypeInsn(Opcodes.CHECKCAST, "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo")
      underlying.visitVarInsn(Opcodes.ASTORE, 1)
      val label1 = new Label()
      underlying.visitLabel(label1)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label2 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNONNULL, label2)
      val label3 = new Label()
      underlying.visitLabel(label3)
      underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/authz/permission/Role", "EMPTY", "Lorg/elasticsearch/xpack/core/security/authz/permission/Role;")
      underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/authz/permission/Role", "EMPTY", "Lorg/elasticsearch/xpack/core/security/authz/permission/Role;")
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo", "<init>", "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V", false)
      val label4 = new Label()
      underlying.visitJumpInsn(Opcodes.GOTO, label4)
      underlying.visitLabel(label2)
      underlying.visitFrame(Opcodes.F_APPEND, 1, Array[AnyRef]("org/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo"), 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitLabel(label4)
      underlying.visitFrame(Opcodes.F_SAME1, 0, null, 1, Array[AnyRef]("org/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo"))
      underlying.visitInsn(Opcodes.ARETURN)
      val label5 = new Label()
      underlying.visitLabel(label5)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;", null, label0, label5, 0)
      underlying.visitLocalVariable("authInfo", "Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;", null, label1, label5, 1)
      underlying.visitMaxs(4, 2)
      underlying.visitEnd()
    }
  }
}

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
/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY without even the implied warranty of
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
      underlying.visitJumpInsn(Opcodes.IFNULL, label2)
      val label3 = new Label()
      underlying.visitLabel(label3)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitInsn(Opcodes.ARETURN)
      underlying.visitLabel(label2)
      underlying.visitFrame(Opcodes.F_APPEND,1, Array("org/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo"), 0, null)
      underlying.visitInsn(Opcodes.ICONST_1)
      underlying.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitInsn(Opcodes.ICONST_0)
      underlying.visitLdcInsn("superuser")
      underlying.visitInsn(Opcodes.AASTORE)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/core/security/authz/permission/Role", "builder", "([Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;", false)
      underlying.visitLdcInsn("all")
      val label4 = new Label()
      underlying.visitLabel(label4)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder", "cluster", "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;", false)
      underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege", "ALL", "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;")
      underlying.visitInsn(Opcodes.ICONST_0)
      underlying.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      val label5 = new Label()
      underlying.visitLabel(label5)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder", "add", "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;", false)
      underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitLdcInsn("*")
      underlying.visitLdcInsn("*")
      underlying.visitInsn(Opcodes.ICONST_0)
      underlying.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege", "<init>", "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V", false)
      val label6 = new Label()
      underlying.visitLabel(label6)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptySet", "()Ljava/util/Set;", false)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder", "addApplicationPrivilege", "(Lorg/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege;Ljava/util/Set;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;", false)
      underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/authz/privilege/Privilege", "ALL", "Lorg/elasticsearch/xpack/core/security/authz/privilege/Privilege;")
      val label7 = new Label()
      underlying.visitLabel(label7)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder", "runAs", "(Lorg/elasticsearch/xpack/core/security/authz/privilege/Privilege;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;", false)
      val label8 = new Label()
      underlying.visitLabel(label8)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder", "build", "()Lorg/elasticsearch/xpack/core/security/authz/permission/Role;", false)
      underlying.visitVarInsn(Opcodes.ASTORE, 2)
      val label9 = new Label()
      underlying.visitLabel(label9)
      underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo", "<init>", "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V", false)
      underlying.visitInsn(Opcodes.ARETURN)
      val label10 = new Label()
      underlying.visitLabel(label10)
      underlying.visitLocalVariable("role", "Lorg/elasticsearch/xpack/core/security/authz/permission/Role;", null, label9, label10, 2)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;", null, label0, label10, 0)
      underlying.visitLocalVariable("authInfo", "Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;", null, label1, label10, 1)
      underlying.visitMaxs(6, 3)
      underlying.visitEnd()
    }
  }
}

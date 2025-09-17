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
package tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.authorization

import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.{File, InputStream}

private [patches] object MockAuthorizationInfoDuringRetrievingUserPrivilegesInAuthorizationService extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/authz/AuthorizationService.class",
      processFileContent = doMockAuthorizationInfo
    )
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
        case "retrieveUserPrivileges" =>
          new RetrieveUserPrivilegesUsingMockAuthorizationInfo(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class RetrieveUserPrivilegesUsingMockAuthorizationInfo(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label
      underlying.visitLabel(label0)
      underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authz/RestrictedIndices")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/support/Automatons", "EMPTY", "Lorg/apache/lucene/util/automaton/Automaton;")
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/core/security/authz/RestrictedIndices", "<init>", "(Lorg/apache/lucene/util/automaton/Automaton;)V", false)
      underlying.visitInsn(Opcodes.ICONST_1)
      underlying.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitInsn(Opcodes.ICONST_0)
      underlying.visitLdcInsn("superuser")
      underlying.visitInsn(Opcodes.AASTORE)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/core/security/authz/permission/Role", "builder", "(Lorg/elasticsearch/xpack/core/security/authz/RestrictedIndices;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;", true)
      underlying.visitLdcInsn("all")
      val label1 = new Label
      underlying.visitLabel(label1)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder", "cluster", "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;", false)
      underlying.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege", "ALL", "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;")
      underlying.visitInsn(Opcodes.ICONST_0)
      underlying.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      val label2 = new Label()
      underlying.visitLabel(label2)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder", "add", "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;", false)
      val label3 = new Label()
      underlying.visitLabel(label3)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder", "build", "()Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;", false)
      underlying.visitVarInsn(Opcodes.ASTORE, 4)
      val label4 = new Label()
      underlying.visitLabel(label4)
      underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo", "<init>", "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V", false)
      underlying.visitVarInsn(Opcodes.ASTORE, 2)
      val label5 = new Label()
      underlying.visitLabel(label5)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authz/AuthorizationService", "getAuthorizationEngineForSubject", "(Lorg/elasticsearch/xpack/core/security/authc/Subject;)Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine;", false)
      underlying.visitVarInsn(Opcodes.ASTORE, 5)
      val label6 = new Label()
      underlying.visitLabel(label6)
      underlying.visitVarInsn(Opcodes.ALOAD, 5)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitVarInsn(Opcodes.ALOAD, 3)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authz/AuthorizationService", "threadContext", "Lorg/elasticsearch/common/util/concurrent/ThreadContext;")
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/action/support/ContextPreservingActionListener", "wrapPreservingContext", "(Lorg/elasticsearch/action/ActionListener;Lorg/elasticsearch/common/util/concurrent/ThreadContext;)Lorg/elasticsearch/action/support/ContextPreservingActionListener;", false)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine", "getUserPrivileges", "(Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;Lorg/elasticsearch/action/ActionListener;)V", true)
      val label7 = new Label
      underlying.visitLabel(label7)
      underlying.visitInsn(Opcodes.RETURN)
      val label8 = new Label
      underlying.visitLabel(label8)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;", null, label0, label8, 0)
      underlying.visitLocalVariable("subject", "Lorg/elasticsearch/xpack/core/security/authc/Subject;", null, label0, label8, 1)
      underlying.visitLocalVariable("authorizationInfo", "Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;", null, label0, label8, 2)
      underlying.visitLocalVariable("listener", "Lorg/elasticsearch/action/ActionListener;", "Lorg/elasticsearch/action/ActionListener<Lorg/elasticsearch/xpack/core/security/action/user/GetUserPrivilegesResponse;>;", label0, label8, 3)
      underlying.visitLocalVariable("role", "Lorg/elasticsearch/xpack/core/security/authz/permission/Role;", null, label4, label8, 4)
      underlying.visitLocalVariable("authorizationEngine", "Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine;", null, label6, label8, 5)
      underlying.visitMaxs(5, 6)
      underlying.visitEnd()
    }
  }
}

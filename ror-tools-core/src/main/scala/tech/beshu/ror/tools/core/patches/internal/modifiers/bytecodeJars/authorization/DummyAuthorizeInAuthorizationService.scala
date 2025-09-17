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

    override def visit(version: Int, access: Int, name: String, signature: String, superName: String, interfaces: Array[String]): Unit = {
      super.visit(version, access, name, signature, superName, interfaces)
      RorGetAuthorizationInfoFromContextMethod.create(this)
    }

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      name match {
        case "authorize" =>
          new DummyAuthorizationMethod(super.visitMethod(access, name, descriptor, signature, exceptions))
        case "retrieveUserPrivileges" =>
          new RetrieveUserPrivilegesUsingRorGetAuthorizationInfoFromContextMethod(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private object RorGetAuthorizationInfoFromContextMethod {

    def create(classVisitor: ClassVisitor): Unit = {
      val methodVisitor = classVisitor.visitMethod(Opcodes.ACC_PRIVATE, "rorGetAuthorizationInfoFromContext", "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;", null, null)
      methodVisitor.visitCode()
      val label0 = new Label()
      methodVisitor.visitLabel(label0)
      methodVisitor.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authz/RestrictedIndices")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/support/Automatons", "EMPTY", "Lorg/apache/lucene/util/automaton/Automaton;")
      methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/core/security/authz/RestrictedIndices", "<init>", "(Lorg/apache/lucene/util/automaton/Automaton;)V", false)
      methodVisitor.visitVarInsn(Opcodes.ASTORE, 1)
      val label1 = new Label()
      methodVisitor.visitLabel(label1)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
      methodVisitor.visitInsn(Opcodes.ICONST_1)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitLdcInsn("superuser")
      methodVisitor.visitInsn(Opcodes.AASTORE)
      val label2 = new Label()
      methodVisitor.visitLabel(label2)
      methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/core/security/authz/permission/Role", "builder", "(Lorg/elasticsearch/xpack/core/security/authz/RestrictedIndices;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;", true)
      methodVisitor.visitLdcInsn("all")
      val label3 = new Label()
      methodVisitor.visitLabel(label3)
      methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
      methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false)
      methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder", "cluster", "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;", false)
      methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege", "ALL", "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;")
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      val label4 = new Label()
      methodVisitor.visitLabel(label4)
      methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder", "add", "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;", false)
      val label5 = new Label()
      methodVisitor.visitLabel(label5)
      methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder", "build", "()Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;", false)
      methodVisitor.visitVarInsn(Opcodes.ASTORE, 2)
      val label6 = new Label()
      methodVisitor.visitLabel(label6)
      methodVisitor.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)
      methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo", "<init>", "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V", false)
      methodVisitor.visitInsn(Opcodes.ARETURN)
      val label7 = new Label()
      methodVisitor.visitLabel(label7)
      methodVisitor.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;", null, label0, label7, 0)
      methodVisitor.visitLocalVariable("restricted", "Lorg/elasticsearch/xpack/core/security/authz/RestrictedIndices;", null, label1, label7, 1)
      methodVisitor.visitLocalVariable("role", "Lorg/elasticsearch/xpack/core/security/authz/permission/Role;", null, label6, label7, 2)
      methodVisitor.visitMaxs(5, 3)
      methodVisitor.visitEnd()
    }
  }

  private class DummyAuthorizationMethod(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      underlying.visitLabel(label0)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authz/AuthorizationService", "threadContext", "Lorg/elasticsearch/common/util/concurrent/ThreadContext;")
      underlying.visitLdcInsn("_authz_info")
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "getTransient", "(Ljava/lang/String;)Ljava/lang/Object;", false)
      val label1 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNONNULL, label1)
      val label2 = new Label()
      underlying.visitLabel(label2)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authz/AuthorizationService", "threadContext", "Lorg/elasticsearch/common/util/concurrent/ThreadContext;")
      underlying.visitLdcInsn("_authz_info")
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authz/AuthorizationService", "rorGetAuthorizationInfoFromContext", "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;", false)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/common/util/concurrent/ThreadContext", "putTransient", "(Ljava/lang/String;Ljava/lang/Object;)V", false)
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
      underlying.visitMaxs(3, 5)
      underlying.visitEnd()
    }
  }

  private class RetrieveUserPrivilegesUsingRorGetAuthorizationInfoFromContextMethod(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      underlying.visitLabel(label0)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authz/AuthorizationService", "getAuthorizationEngineForSubject", "(Lorg/elasticsearch/xpack/core/security/authc/Subject;)Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine;", false)
      underlying.visitVarInsn(Opcodes.ASTORE, 4)
      val label1 = new Label()
      underlying.visitLabel(label1)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/authz/AuthorizationService", "rorGetAuthorizationInfoFromContext", "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;", false)
      underlying.visitVarInsn(Opcodes.ALOAD, 3)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(Opcodes.GETFIELD, "org/elasticsearch/xpack/security/authz/AuthorizationService", "threadContext", "Lorg/elasticsearch/common/util/concurrent/ThreadContext;")
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/action/support/ContextPreservingActionListener", "wrapPreservingContext", "(Lorg/elasticsearch/action/ActionListener;Lorg/elasticsearch/common/util/concurrent/ThreadContext;)Lorg/elasticsearch/action/support/ContextPreservingActionListener;", false)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine", "getUserPrivileges", "(Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;Lorg/elasticsearch/action/ActionListener;)V", true)
      val label2 = new Label()
      underlying.visitLabel(label2)
      underlying.visitInsn(Opcodes.RETURN)
      val label3 = new Label()
      underlying.visitLabel(label3)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;", null, label0, label3, 0)
      underlying.visitLocalVariable("subject", "Lorg/elasticsearch/xpack/core/security/authc/Subject;", null, label0, label3, 1)
      underlying.visitLocalVariable("authorizationInfo", "Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;", null, label0, label3, 2)
      underlying.visitLocalVariable("listener", "Lorg/elasticsearch/action/ActionListener;", "Lorg/elasticsearch/action/ActionListener<Lorg/elasticsearch/xpack/core/security/action/user/GetUserPrivilegesResponse;>;", label0, label3, 3)
      underlying.visitLocalVariable("authorizationEngine", "Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine;", null, label1, label3, 4)
      underlying.visitMaxs(4, 5)
      underlying.visitEnd()
    }
  }
}

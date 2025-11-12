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

// todo: add desc
private [patches] object SimpleRoleAllowingEverything extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/authz/permission/SimpleRole.class",
      processFileContent = doCreatePermissiveSimpleRole
    )
  }

  private def doCreatePermissiveSimpleRole(moduleInputStream: InputStream) = {
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
        case "checkIndicesAction" =>
          new CheckIndicesActionMethodReturningTrue(super.visitMethod(access, name, descriptor, signature, exceptions))
//        case "checkIndicesPrivileges" =>
//          new CheckIndicesPrivilegesMethodReturningTrue(super.visitMethod(access, name, descriptor, signature, exceptions))
        case "checkClusterAction" =>
          new CheckClusterActionMethodReturningTrue(super.visitMethod(access, name, descriptor, signature, exceptions))
        case "grants" =>
          new GrantsMethodReturningTrue(super.visitMethod(access, name, descriptor, signature, exceptions))
        case "checkApplicationResourcePrivileges" =>
          new CheckApplicationResourcePrivilegesMethodReturningTrue(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class CheckIndicesActionMethodReturningTrue(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      underlying.visitLabel(label0)
      underlying.visitInsn(Opcodes.ICONST_1)
      underlying.visitInsn(Opcodes.IRETURN)
      val label1 = new Label()
      underlying.visitLabel(label1)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;", null, label0, label1, 0)
      underlying.visitLocalVariable("action", "Ljava/lang/String;", null, label0, label1, 1)
      underlying.visitMaxs(1, 2)
      underlying.visitEnd()
    }
  }

  private class CheckIndicesPrivilegesMethodReturningTrue(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      underlying.visitLabel(label0)
      underlying.visitInsn(Opcodes.ICONST_1)
      underlying.visitInsn(Opcodes.IRETURN)
      val label1 = new Label()
      underlying.visitLabel(label1)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;", null, label0, label1, 0)
      underlying.visitLocalVariable("checkForIndexPatterns", "Ljava/util/Set;", "Ljava/util/Set<Ljava/lang/String;>;", label0, label1, 1)
      underlying.visitLocalVariable("allowRestrictedIndices", "Z", null, label0, label1, 2)
      underlying.visitLocalVariable("checkForPrivileges", "Ljava/util/Set;", "Ljava/util/Set<Ljava/lang/String;>;", label0, label1, 3)
      underlying.visitLocalVariable("resourcePrivilegesMapBuilder", "Lorg/elasticsearch/xpack/core/security/authz/permission/ResourcePrivilegesMap$Builder;", null, label0, label1, 4)
      underlying.visitMaxs(1, 5)
      underlying.visitEnd()
    }
  }

  private class CheckClusterActionMethodReturningTrue(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      underlying.visitLabel(label0)
      underlying.visitInsn(Opcodes.ICONST_1)
      underlying.visitInsn(Opcodes.IRETURN)
      val label1 = new Label()
      underlying.visitLabel(label1)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;", null, label0, label1, 0)
      underlying.visitLocalVariable("action", "Ljava/lang/String;", null, label0, label1, 1)
      underlying.visitLocalVariable("request", "Lorg/elasticsearch/transport/TransportRequest;", null, label0, label1, 2)
      underlying.visitLocalVariable("authentication", "Lorg/elasticsearch/xpack/core/security/authc/Authentication;", null, label0, label1, 3)
      underlying.visitMaxs(1, 4)
      underlying.visitEnd()
    }
  }

  private class GrantsMethodReturningTrue(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      underlying.visitLabel(label0)
      underlying.visitInsn(Opcodes.ICONST_1)
      underlying.visitInsn(Opcodes.IRETURN)
      val label1 = new Label()
      underlying.visitLabel(label1)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;", null, label0, label1, 0)
      underlying.visitLocalVariable("clusterPrivilege", "Lorg/elasticsearch/xpack/core/security/authz/privilege/ClusterPrivilege;", null, label0, label1, 1)
      underlying.visitMaxs(1, 2)
      underlying.visitEnd()
    }
  }

  private class CheckApplicationResourcePrivilegesMethodReturningTrue(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      underlying.visitLabel(label0)
      underlying.visitInsn(Opcodes.ICONST_1)
      underlying.visitInsn(Opcodes.IRETURN)
      val label1 = new Label()
      underlying.visitLabel(label1)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;", null, label0, label1, 0)
      underlying.visitLocalVariable("applicationName", "Ljava/lang/String;", null, label0, label1, 1)
      underlying.visitLocalVariable("checkForResources", "Ljava/util/Set;", "Ljava/util/Set<Ljava/lang/String;>;", label0, label1, 2)
      underlying.visitLocalVariable("checkForPrivilegeNames", "Ljava/util/Set;", "Ljava/util/Set<Ljava/lang/String;>;", label0, label1, 3)
      underlying.visitLocalVariable("storedPrivileges", "Ljava/util/Collection;", "Ljava/util/Collection<Lorg/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilegeDescriptor;>;", label0, label1, 4)
      underlying.visitLocalVariable("resourcePrivilegesMapBuilder", "Lorg/elasticsearch/xpack/core/security/authz/permission/ResourcePrivilegesMap$Builder;", null, label0, label1, 5)
      underlying.visitMaxs(1, 6)
      underlying.visitEnd()
    }
  }

}

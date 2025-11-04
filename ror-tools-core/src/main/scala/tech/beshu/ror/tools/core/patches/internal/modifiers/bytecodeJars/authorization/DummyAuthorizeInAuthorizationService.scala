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

import better.files.File
import just.semver.SemVer
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier
import tech.beshu.ror.tools.core.utils.EsUtil.{es670, es7160, es800, es810, es820, es830}

import java.io.InputStream

/*
  Elasticsearch’s AuthorizationService blocks requests without a full authorization flow. ROR needs to bypass this
  in controlled scenarios. This bytecode modifier injects a helper that builds a superuser-like AuthorizationInfo
  and rewrites authorize(…) to stash it in ThreadContext and immediately succeed, effectively short-circuiting
  X-Pack authorization.
*/
private [patches] class DummyAuthorizeInAuthorizationService(esVersion: SemVer)
  extends BytecodeJarModifier {

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
      RorCreateArtificialAuthorizationInfoMethod.create(this)
    }

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      name match {
        case "authorize" =>
          esVersion match {
            case v if v >= es7160 =>
              new DummyAuthorizationMethodForEsGreaterOrEqual7160(super.visitMethod(access, name, descriptor, signature, exceptions))
            case v if v >= es670 =>
              new DummyAuthorizationMethodForEsGreaterOrEqual670(super.visitMethod(access, name, descriptor, signature, exceptions))
            case _ =>
              super.visitMethod(access, name, descriptor, signature, exceptions)
          }
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private object RorCreateArtificialAuthorizationInfoMethod {

    def create(classVisitor: ClassVisitor): Unit = {
      esVersion match {
        case v if v >= es830 => createForEsGreaterOrEqual830(classVisitor)
        case v if v >= es820 => createForEsGreaterOrEqual820(classVisitor)
        case v if v >= es810 => createForEsGreaterOrEqual810(classVisitor)
        case v if v >= es800 => createForEsGreaterOrEqual800(classVisitor)
        case v if v >= es670 => createForEsGreaterOrEqual670(classVisitor)
        case _ => // nothing
      }
    }

    private def createForEsGreaterOrEqual830(classVisitor: ClassVisitor): Unit = {
      // private AuthorizationEngine.AuthorizationInfo rorCreateArtificialAuthorizationInfo() { ... }
      val methodVisitor = classVisitor.visitMethod(
        Opcodes.ACC_PRIVATE,
        "rorCreateArtificialAuthorizationInfo",
        "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
        null,
        null
      )
      methodVisitor.visitCode()

      val l0 = new Label()
      methodVisitor.visitLabel(l0)

      // RestrictedIndices restricted = new RestrictedIndices(Automatons.EMPTY)
      methodVisitor.visitTypeInsn(
        Opcodes.NEW,
        "org/elasticsearch/xpack/core/security/authz/RestrictedIndices"
      )
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitFieldInsn(
        Opcodes.GETSTATIC,
        "org/elasticsearch/xpack/core/security/support/Automatons",
        "EMPTY",
        "Lorg/apache/lucene/util/automaton/Automaton;"
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "org/elasticsearch/xpack/core/security/authz/RestrictedIndices",
        "<init>",
        "(Lorg/apache/lucene/util/automaton/Automaton;)V",
        /* itf = */ false
      )
      methodVisitor.visitVarInsn(Opcodes.ASTORE, 1)

      // Role role = Role.builder(restricted, new String[] { "superuser" })
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 1) // restricted
      methodVisitor.visitInsn(Opcodes.ICONST_1)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitLdcInsn("superuser")
      methodVisitor.visitInsn(Opcodes.AASTORE)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/xpack/core/security/authz/permission/Role",
        "builder",
        "(Lorg/elasticsearch/xpack/core/security/authz/RestrictedIndices;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        /* itf = */ true // IMPORTANT: Role is an interface in many 8.x builds; set to true to avoid ICCE
      )

      // .cluster(Collections.singleton("all"), Collections.emptyList())
      methodVisitor.visitLdcInsn("all")
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "singleton",
        "(Ljava/lang/Object;)Ljava/util/Set;",
        false
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "emptyList",
        "()Ljava/util/List;",
        false
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "cluster",
        "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .add(IndexPrivilege.ALL, new String[] { "*" })
      methodVisitor.visitFieldInsn(
        Opcodes.GETSTATIC,
        "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege",
        "ALL",
        "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;"
      )
      methodVisitor.visitInsn(Opcodes.ICONST_1)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitInsn(Opcodes.AASTORE)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "add",
        "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .build()  // SimpleRole (impl of Role)
      val l1 = new Label()
      methodVisitor.visitLabel(l1)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "build",
        "()Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;",
        false
      )
      methodVisitor.visitVarInsn(Opcodes.ASTORE, 2) // role

      // return new RBACEngine.RBACAuthorizationInfo(role, role)
      val l2 = new Label()
      methodVisitor.visitLabel(l2)
      methodVisitor.visitTypeInsn(
        Opcodes.NEW,
        "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo"
      )
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo",
        "<init>",
        "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V",
        false
      )
      methodVisitor.visitInsn(Opcodes.ARETURN)

      // Locals & max stack/locals
      val lEnd = new Label()
      methodVisitor.visitLabel(lEnd)
      methodVisitor.visitLocalVariable(
        "this",
        "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;",
        null,
        l0,
        lEnd,
        0
      )
      methodVisitor.visitLocalVariable(
        "restricted",
        "Lorg/elasticsearch/xpack/core/security/authz/RestrictedIndices;",
        null,
        l0,
        lEnd,
        1
      )
      methodVisitor.visitLocalVariable(
        "role",
        "Lorg/elasticsearch/xpack/core/security/authz/permission/Role;",
        null,
        l1,
        lEnd,
        2
      )
      methodVisitor.visitMaxs(6, 3)
      methodVisitor.visitEnd()
    }

    private def createForEsGreaterOrEqual820(classVisitor: ClassVisitor): Unit = {
      // private AuthorizationEngine.AuthorizationInfo rorCreateArtificialAuthorizationInfo()
      val methodVisitor = classVisitor.visitMethod(
        Opcodes.ACC_PRIVATE,
        "rorCreateArtificialAuthorizationInfo",
        "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
        null,
        null
      )
      methodVisitor.visitCode()

      val label0 = new Label()
      methodVisitor.visitLabel(label0)

      // RestrictedIndices restricted = new RestrictedIndices(Automatons.EMPTY)
      methodVisitor.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authz/RestrictedIndices")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitFieldInsn(
        Opcodes.GETSTATIC,
        "org/elasticsearch/xpack/core/security/support/Automatons",
        "EMPTY",
        "Lorg/apache/lucene/util/automaton/Automaton;"
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "org/elasticsearch/xpack/core/security/authz/RestrictedIndices",
        "<init>",
        "(Lorg/apache/lucene/util/automaton/Automaton;)V",
        false
      )
      methodVisitor.visitVarInsn(Opcodes.ASTORE, 1)

      // Role role = Role.builder(restricted, new String[] {"superuser"})
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
      methodVisitor.visitInsn(Opcodes.ICONST_1)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitLdcInsn("superuser")
      methodVisitor.visitInsn(Opcodes.AASTORE)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/xpack/core/security/authz/permission/Role",
        "builder",
        "(Lorg/elasticsearch/xpack/core/security/authz/RestrictedIndices;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        true
      )

      // .cluster(Collections.singleton("all"), Collections.emptyList())
      methodVisitor.visitLdcInsn("all")
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "singleton",
        "(Ljava/lang/Object;)Ljava/util/Set;",
        false
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "emptyList",
        "()Ljava/util/List;",
        false
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "cluster",
        "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .add(IndexPrivilege.ALL, new String[] {"*"})
      methodVisitor.visitFieldInsn(
        Opcodes.GETSTATIC,
        "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege",
        "ALL",
        "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;"
      )
      methodVisitor.visitInsn(Opcodes.ICONST_1)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitInsn(Opcodes.AASTORE)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "add",
        "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .addApplicationPrivilege(new ApplicationPrivilege("*","*", new String[0]), Collections.singleton("*"))
      methodVisitor.visitTypeInsn(
        Opcodes.NEW,
        "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege"
      )
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege",
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V",
        false
      )
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "singleton",
        "(Ljava/lang/Object;)Ljava/util/Set;",
        false
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "addApplicationPrivilege",
        "(Lorg/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege;Ljava/util/Set;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .build()
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "build",
        "()Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;",
        false
      )
      methodVisitor.visitVarInsn(Opcodes.ASTORE, 2)

      // return new RBACAuthorizationInfo(role, role)
      methodVisitor.visitTypeInsn(
        Opcodes.NEW,
        "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo"
      )
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 2)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo",
        "<init>",
        "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V",
        false
      )
      methodVisitor.visitInsn(Opcodes.ARETURN)

      val label1 = new Label()
      methodVisitor.visitLabel(label1)

      // locals: this, restricted, role
      methodVisitor.visitLocalVariable(
        "this",
        "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;",
        null,
        label0,
        label1,
        0
      )
      methodVisitor.visitLocalVariable(
        "restricted",
        "Lorg/elasticsearch/xpack/core/security/authz/RestrictedIndices;",
        null,
        label0,
        label1,
        1
      )
      methodVisitor.visitLocalVariable(
        "role",
        "Lorg/elasticsearch/xpack/core/security/authz/permission/Role;",
        null,
        label0,
        label1,
        2
      )

      methodVisitor.visitMaxs(6, 3)
      methodVisitor.visitEnd()
    }

    private def createForEsGreaterOrEqual810(classVisitor: ClassVisitor): Unit = {
      // private AuthorizationEngine.AuthorizationInfo rorCreateArtificialAuthorizationInfo() { ... }
      val methodVisitor = classVisitor.visitMethod(
        Opcodes.ACC_PRIVATE,
        "rorCreateArtificialAuthorizationInfo",
        "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
        null,
        null
      )
      methodVisitor.visitCode()

      val label0 = new Label()
      methodVisitor.visitLabel(label0)

      // Role.Builder b = Role.builder(Automatons.EMPTY, new String[] { "superuser" })
      methodVisitor.visitFieldInsn(
        Opcodes.GETSTATIC,
        "org/elasticsearch/xpack/core/security/support/Automatons",
        "EMPTY",
        "Lorg/apache/lucene/util/automaton/Automaton;"
      )
      methodVisitor.visitInsn(Opcodes.ICONST_1)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitLdcInsn("superuser")
      methodVisitor.visitInsn(Opcodes.AASTORE)
      val label1 = new Label()
      methodVisitor.visitLabel(label1)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/xpack/core/security/authz/permission/Role",
        "builder",
        "(Lorg/apache/lucene/util/automaton/Automaton;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        /* itf = */ true // static method on an interface
      )

      // b.cluster(Collections.singleton("all"), Collections.emptyList())
      methodVisitor.visitLdcInsn("all")
      val label2 = new Label()
      methodVisitor.visitLabel(label2)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "singleton",
        "(Ljava/lang/Object;)Ljava/util/Set;",
        false
      )
      val label3 = new Label()
      methodVisitor.visitLabel(label3)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "emptyList",
        "()Ljava/util/List;",
        false
      )
      val label4 = new Label()
      methodVisitor.visitLabel(label4)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "cluster",
        "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // b.add(IndexPrivilege.ALL, new String[] { "*" })
      methodVisitor.visitFieldInsn(
        Opcodes.GETSTATIC,
        "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege",
        "ALL",
        "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;"
      )
      methodVisitor.visitInsn(Opcodes.ICONST_1)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitInsn(Opcodes.AASTORE)
      val label5 = new Label()
      methodVisitor.visitLabel(label5)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "add",
        "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // b.addApplicationPrivilege(new ApplicationPrivilege("*","*",new String[0]), Collections.singleton("*"))
      methodVisitor.visitTypeInsn(
        Opcodes.NEW,
        "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege"
      )
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege",
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V",
        false
      )
      methodVisitor.visitLdcInsn("*")
      val label6 = new Label()
      methodVisitor.visitLabel(label6)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "singleton",
        "(Ljava/lang/Object;)Ljava/util/Set;",
        false
      )
      val label7 = new Label()
      methodVisitor.visitLabel(label7)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "addApplicationPrivilege",
        "(Lorg/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege;Ljava/util/Set;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // Role role = b.build()   // bytecode returns SimpleRole (impl of Role)
      val label8 = new Label()
      methodVisitor.visitLabel(label8)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "build",
        "()Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;",
        false
      )
      methodVisitor.visitVarInsn(Opcodes.ASTORE, 1)

      // return new RBACEngine.RBACAuthorizationInfo(role, role)
      val label9 = new Label()
      methodVisitor.visitLabel(label9)
      methodVisitor.visitTypeInsn(
        Opcodes.NEW,
        "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo"
      )
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo",
        "<init>",
        "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V",
        false
      )
      methodVisitor.visitInsn(Opcodes.ARETURN)

      // Local vars + max stack/locals
      val label10 = new Label()
      methodVisitor.visitLabel(label10)
      methodVisitor.visitLocalVariable(
        "this",
        "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;",
        null,
        label0,
        label10,
        0
      )
      methodVisitor.visitLocalVariable(
        "role",
        "Lorg/elasticsearch/xpack/core/security/authz/permission/Role;",
        null,
        label9,
        label10,
        1
      )
      methodVisitor.visitMaxs(6, 2)
      methodVisitor.visitEnd()
    }

    private def createForEsGreaterOrEqual800(classVisitor: ClassVisitor): Unit = {
      // private AuthorizationEngine.AuthorizationInfo rorCreateArtificialAuthorizationInfo()
      val methodVisitor = classVisitor.visitMethod(
        Opcodes.ACC_PRIVATE,
        "rorCreateArtificialAuthorizationInfo",
        "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
        null,
        null
      )

      methodVisitor.visitCode()
      val label0 = new Label()
      methodVisitor.visitLabel(label0)

      // Role.builder(Automatons.EMPTY, new String[] {"superuser"})
      methodVisitor.visitFieldInsn(
        Opcodes.GETSTATIC,
        "org/elasticsearch/xpack/core/security/support/Automatons",
        "EMPTY",
        "Lorg/apache/lucene/util/automaton/Automaton;"
      )
      methodVisitor.visitInsn(Opcodes.ICONST_1)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitLdcInsn("superuser")
      methodVisitor.visitInsn(Opcodes.AASTORE)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/xpack/core/security/authz/permission/Role",
        "builder",
        "(Lorg/apache/lucene/util/automaton/Automaton;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .cluster(Collections.singleton("all"), Collections.emptyList())
      methodVisitor.visitLdcInsn("all")
      val label1 = new Label()
      methodVisitor.visitLabel(label1)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "singleton",
        "(Ljava/lang/Object;)Ljava/util/Set;",
        false
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "emptyList",
        "()Ljava/util/List;",
        false
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "cluster",
        "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .add(IndexPrivilege.ALL, new String[] {"*"})
      methodVisitor.visitFieldInsn(
        Opcodes.GETSTATIC,
        "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege",
        "ALL",
        "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;"
      )
      methodVisitor.visitInsn(Opcodes.ICONST_1)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitInsn(Opcodes.AASTORE)
      val label2 = new Label()
      methodVisitor.visitLabel(label2)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "add",
        "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .addApplicationPrivilege(new ApplicationPrivilege("*","*", new String[0]), Collections.singleton("*"))
      methodVisitor.visitTypeInsn(
        Opcodes.NEW,
        "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege"
      )
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege",
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V",
        false
      )
      methodVisitor.visitLdcInsn("*")
      val label3 = new Label()
      methodVisitor.visitLabel(label3)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "singleton",
        "(Ljava/lang/Object;)Ljava/util/Set;",
        false
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "addApplicationPrivilege",
        "(Lorg/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege;Ljava/util/Set;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .build()
      val label4 = new Label()
      methodVisitor.visitLabel(label4)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "build",
        "()Lorg/elasticsearch/xpack/core/security/authz/permission/Role;",
        false
      )
      methodVisitor.visitVarInsn(Opcodes.ASTORE, 1)

      // return new RBACEngine$RBACAuthorizationInfo(role, role)
      val label5 = new Label()
      methodVisitor.visitLabel(label5)
      methodVisitor.visitTypeInsn(
        Opcodes.NEW,
        "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo"
      )
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo",
        "<init>",
        "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V",
        false
      )
      methodVisitor.visitInsn(Opcodes.ARETURN)

      val label6 = new Label()
      methodVisitor.visitLabel(label6)
      methodVisitor.visitLocalVariable(
        "this",
        "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;",
        null,
        label0,
        label6,
        0
      )
      methodVisitor.visitLocalVariable(
        "role",
        "Lorg/elasticsearch/xpack/core/security/authz/permission/Role;",
        null,
        label5,
        label6,
        1
      )

      methodVisitor.visitMaxs(6, 2)
      methodVisitor.visitEnd()
    }

    private def createForEsGreaterOrEqual670(classVisitor: ClassVisitor): Unit = {
      // private AuthorizationEngine.AuthorizationInfo rorCreateArtificialAuthorizationInfo()
      val methodVisitor = classVisitor.visitMethod(
        Opcodes.ACC_PRIVATE,
        "rorCreateArtificialAuthorizationInfo",
        "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
        null,
        null
      )

      methodVisitor.visitCode()
      val label0 = new org.objectweb.asm.Label()
      methodVisitor.visitLabel(label0)

      // Role.builder(new String[] { "superuser" })
      methodVisitor.visitInsn(Opcodes.ICONST_1)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitLdcInsn("superuser")
      methodVisitor.visitInsn(Opcodes.AASTORE)
      val label1 = new org.objectweb.asm.Label()
      methodVisitor.visitLabel(label1)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/xpack/core/security/authz/permission/Role",
        "builder",
        "([Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .cluster(Collections.singleton("all"), Collections.emptyList())
      methodVisitor.visitLdcInsn("all")
      val label2 = new org.objectweb.asm.Label()
      methodVisitor.visitLabel(label2)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "singleton",
        "(Ljava/lang/Object;)Ljava/util/Set;",
        false
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "emptyList",
        "()Ljava/util/List;",
        false
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "cluster",
        "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .add(IndexPrivilege.ALL, new String[] { "*" })
      methodVisitor.visitFieldInsn(
        Opcodes.GETSTATIC,
        "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege",
        "ALL",
        "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;"
      )
      methodVisitor.visitInsn(Opcodes.ICONST_1)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitInsn(Opcodes.AASTORE)
      val label3 = new org.objectweb.asm.Label()
      methodVisitor.visitLabel(label3)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "add",
        "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .addApplicationPrivilege(new ApplicationPrivilege("*","*", new String[0]), Collections.singleton("*"))
      methodVisitor.visitTypeInsn(
        Opcodes.NEW,
        "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege"
      )
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitLdcInsn("*")
      methodVisitor.visitInsn(Opcodes.ICONST_0)
      methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege",
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V",
        false
      )
      methodVisitor.visitLdcInsn("*")
      val label4 = new org.objectweb.asm.Label()
      methodVisitor.visitLabel(label4)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Collections",
        "singleton",
        "(Ljava/lang/Object;)Ljava/util/Set;",
        false
      )
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "addApplicationPrivilege",
        "(Lorg/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege;Ljava/util/Set;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
        false
      )

      // .build()
      val label5 = new org.objectweb.asm.Label()
      methodVisitor.visitLabel(label5)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
        "build",
        "()Lorg/elasticsearch/xpack/core/security/authz/permission/Role;",
        false
      )
      methodVisitor.visitVarInsn(Opcodes.ASTORE, 1)

      // return new RBACEngine$RBACAuthorizationInfo(role, role)
      val label6 = new org.objectweb.asm.Label()
      methodVisitor.visitLabel(label6)
      methodVisitor.visitTypeInsn(
        Opcodes.NEW,
        "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo"
      )
      methodVisitor.visitInsn(Opcodes.DUP)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
      methodVisitor.visitVarInsn(Opcodes.ALOAD, 1)
      methodVisitor.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo",
        "<init>",
        "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V",
        false
      )
      methodVisitor.visitInsn(Opcodes.ARETURN)

      val label7 = new org.objectweb.asm.Label()
      methodVisitor.visitLabel(label7)
      methodVisitor.visitLocalVariable(
        "this",
        "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;",
        null,
        label0,
        label7,
        0
      )
      methodVisitor.visitLocalVariable(
        "role",
        "Lorg/elasticsearch/xpack/core/security/authz/permission/Role;",
        null,
        label6,
        label7,
        1
      )

      methodVisitor.visitMaxs(6, 2)
      methodVisitor.visitEnd()
    }
  }

  private class DummyAuthorizationMethodForEsGreaterOrEqual7160(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      // -- begin: ensure _authz_info present in ThreadContext --
      val label0 = new Label()
      underlying.visitLabel(label0)

      // if (threadContext.getTransient("_authz_info") == null)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_authz_info")
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "getTransient",
        "(Ljava/lang/String;)Ljava/lang/Object;",
        /* itf = */ false
      )
      val label1 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNONNULL, label1)

      // threadContext.putTransient("_authz_info", this.rorCreateArtificialAuthorizationInfo())
      val label2 = new Label()
      underlying.visitLabel(label2)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_authz_info")
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      val label3 = new Label()
      underlying.visitLabel(label3)
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "rorCreateArtificialAuthorizationInfo",
        "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
        /* itf = */ false
      )
      val label4 = new Label()
      underlying.visitLabel(label4)
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "putTransient",
        "(Ljava/lang/String;Ljava/lang/Object;)V",
        /* itf = */ false
      )
      // -- end: ensure _authz_info --
      // -- begin: ensure _indices_permissions present (allow all) --
      underlying.visitLabel(label1)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)

      // if (threadContext.getTransient("_indices_permissions") == null)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_indices_permissions")
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "getTransient",
        "(Ljava/lang/String;)Ljava/lang/Object;",
        /* itf = */ false
      )
      val label5 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNONNULL, label5)

      // threadContext.putTransient("_indices_permissions", IndicesAccessControl.allowAll())
      val label6 = new Label()
      underlying.visitLabel(label6)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_indices_permissions")
      val label7 = new Label()
      underlying.visitLabel(label7)
      underlying.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "org/elasticsearch/xpack/core/security/authz/accesscontrol/IndicesAccessControl",
        "allowAll",
        "()Lorg/elasticsearch/xpack/core/security/authz/accesscontrol/IndicesAccessControl;",
        /* itf = */ false
      )
      val label8 = new Label()
      underlying.visitLabel(label8)
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "putTransient",
        "(Ljava/lang/String;Ljava/lang/Object;)V",
        /* itf = */ false
      )
      // -- end: ensure _indices_permissions --
      // listener.onResponse(null)
      underlying.visitLabel(label5)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitInsn(Opcodes.ACONST_NULL)
      underlying.visitMethodInsn(
        Opcodes.INVOKEINTERFACE,
        "org/elasticsearch/action/ActionListener",
        "onResponse",
        "(Ljava/lang/Object;)V",
        /* itf = */ true
      )

      // return;
      val label9 = new Label()
      underlying.visitLabel(label9)
      underlying.visitInsn(Opcodes.RETURN)

      // locals/signature bookkeeping
      val label10 = new Label()
      underlying.visitLabel(label10)
      underlying.visitLocalVariable(
        "this",
        "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;",
        null,
        label0,
        label10,
        0
      )
      underlying.visitLocalVariable(
        "authentication",
        "Lorg/elasticsearch/xpack/core/security/authc/Authentication;",
        null,
        label0,
        label10,
        1
      )
      underlying.visitLocalVariable(
        "action",
        "Ljava/lang/String;",
        null,
        label0,
        label10,
        2
      )
      underlying.visitLocalVariable(
        "originalRequest",
        "Lorg/elasticsearch/transport/TransportRequest;",
        null,
        label0,
        label10,
        3
      )
      underlying.visitLocalVariable(
        "listener",
        "Lorg/elasticsearch/action/ActionListener;",
        "Lorg/elasticsearch/action/ActionListener<Ljava/lang/Void;>;",
        label0,
        label10,
        4
      )
      underlying.visitMaxs(3, 5)
      underlying.visitEnd()
    }
  }

  private class DummyAuthorizationMethodForEsGreaterOrEqual670(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()

      val label0 = new Label()
      underlying.visitLabel(label0)

      // if (threadContext.getTransient("_authz_info") == null)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_authz_info")
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "getTransient",
        "(Ljava/lang/String;)Ljava/lang/Object;",
        false
      )
      val label1 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNONNULL, label1)

      // threadContext.putTransient("_authz_info", this.rorCreateArtificialAuthorizationInfo())
      val label2 = new Label()
      underlying.visitLabel(label2)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_authz_info")
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      val label3 = new Label()
      underlying.visitLabel(label3)
      underlying.visitMethodInsn(
        Opcodes.INVOKESPECIAL, // private helper on this
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "rorCreateArtificialAuthorizationInfo",
        "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
        false
      )
      val label4 = new Label()
      underlying.visitLabel(label4)
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "putTransient",
        "(Ljava/lang/String;Ljava/lang/Object;)V",
        false
      )

      // if (threadContext.getTransient("_indices_permissions") == null)
      underlying.visitLabel(label1)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_indices_permissions")
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "getTransient",
        "(Ljava/lang/String;)Ljava/lang/Object;",
        false
      )
      val label5 = new Label()
      underlying.visitJumpInsn(Opcodes.IFNONNULL, label5)

      // threadContext.putTransient("_indices_permissions", IndicesAccessControl.ALLOW_ALL)
      val label6 = new Label()
      underlying.visitLabel(label6)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitFieldInsn(
        Opcodes.GETFIELD,
        "org/elasticsearch/xpack/security/authz/AuthorizationService",
        "threadContext",
        "Lorg/elasticsearch/common/util/concurrent/ThreadContext;"
      )
      underlying.visitLdcInsn("_indices_permissions")
      underlying.visitFieldInsn(
        Opcodes.GETSTATIC,
        "org/elasticsearch/xpack/core/security/authz/accesscontrol/IndicesAccessControl",
        "ALLOW_ALL",
        "Lorg/elasticsearch/xpack/core/security/authz/accesscontrol/IndicesAccessControl;"
      )
      underlying.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "org/elasticsearch/common/util/concurrent/ThreadContext",
        "putTransient",
        "(Ljava/lang/String;Ljava/lang/Object;)V",
        false
      )

      // listener.onResponse(null)
      underlying.visitLabel(label5)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitInsn(Opcodes.ACONST_NULL)
      underlying.visitMethodInsn(
        Opcodes.INVOKEINTERFACE,
        "org/elasticsearch/action/ActionListener",
        "onResponse",
        "(Ljava/lang/Object;)V",
        true
      )

      // return;
      val label7 = new Label()
      underlying.visitLabel(label7)
      underlying.visitInsn(Opcodes.RETURN)

      // locals / stack
      val label8 = new Label()
      underlying.visitLabel(label8)
      underlying.visitLocalVariable(
        "this",
        "Lorg/elasticsearch/xpack/security/authz/AuthorizationService;",
        null,
        label0,
        label8,
        0
      )
      underlying.visitLocalVariable(
        "authentication",
        "Lorg/elasticsearch/xpack/core/security/authc/Authentication;",
        null,
        label0,
        label8,
        1
      )
      underlying.visitLocalVariable("action", "Ljava/lang/String;", null, label0, label8, 2)
      underlying.visitLocalVariable(
        "originalRequest",
        "Lorg/elasticsearch/transport/TransportRequest;",
        null,
        label0,
        label8,
        3
      )
      underlying.visitLocalVariable(
        "listener",
        "Lorg/elasticsearch/action/ActionListener;",
        "Lorg/elasticsearch/action/ActionListener<Ljava/lang/Void;>;",
        label0,
        label8,
        4
      )
      underlying.visitMaxs(3, 5)
      underlying.visitEnd()
    }
  }

}

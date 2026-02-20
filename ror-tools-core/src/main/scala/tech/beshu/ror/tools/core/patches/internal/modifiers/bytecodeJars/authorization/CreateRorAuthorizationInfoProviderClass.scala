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
import tech.beshu.ror.tools.core.utils.EsUtil.{es670, es800, es810, es820, es830}

/**
 * Adds a new `org.elasticsearch.xpack.security.authz.RorAuthorizationInfoProvider` class.
 *
 * The class is a singleton-style provider exposing `public static get()` that returns a prebuilt
 * `AuthorizationEngine.AuthorizationInfo` instance. The instance is created once at class
 * initialization time and represents an artificial, highly-privileged authorization context
 * (built from a role equivalent to “superuser”, with cluster privilege "all" and broad index
 * permissions on "*", plus application privileges where supported by the ES version).
 */
private[patches] class CreateRorAuthorizationInfoProviderClass private(esVersion: SemVer)
  extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    addNewFileToJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/authz/RorAuthorizationInfoProvider.class",
      content = rorAuthorizationInfoProviderClass()
    )
  }

  private def rorAuthorizationInfoProviderClass(): Array[Byte] = {
    val cw = new ClassWriter(0)
    val name = "org/elasticsearch/xpack/security/authz/RorAuthorizationInfoProvider"
    val aiDesc =
      "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;"
    val aiFieldDesc =
      "Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;"

    // use java 8 bytecode level for proper interface method invocation
    cw.visit(
      Opcodes.V1_8,
      Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
      name,
      null,
      "java/lang/Object",
      null
    )

    // private static final RorAuthorizationInfoProvider INSTANCE;
    cw.visitField(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
      "INSTANCE",
      s"L$name;",
      null,
      null
    ).visitEnd()

    // private final AuthorizationEngine.AuthorizationInfo authorizationInfo;
    cw.visitField(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
      "authorizationInfo",
      aiFieldDesc,
      null,
      null
    ).visitEnd()

    // static initializer: INSTANCE = new RorAuthorizationInfoProvider();
    var mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
    mv.visitCode()
    mv.visitTypeInsn(Opcodes.NEW, name)
    mv.visitInsn(Opcodes.DUP)
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "()V", false)
    mv.visitFieldInsn(Opcodes.PUTSTATIC, name, "INSTANCE", s"L$name;")
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(2, 0)
    mv.visitEnd()

    // private <init>() { super(); this.authorizationInfo = rorCreateArtificialAuthorizationInfo(); }
    mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
    mv.visitCode()
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(
      Opcodes.INVOKESTATIC,
      name,
      "rorCreateArtificialAuthorizationInfo",
      aiDesc,
      false
    )
    mv.visitFieldInsn(Opcodes.PUTFIELD, name, "authorizationInfo", aiFieldDesc)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(2, 1)
    mv.visitEnd()

    // public static AuthorizationInfo get() { return INSTANCE.authorizationInfo; }
    mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get", aiDesc, null, null)
    mv.visitCode()
    mv.visitFieldInsn(Opcodes.GETSTATIC, name, "INSTANCE", s"L$name;")
    mv.visitFieldInsn(Opcodes.GETFIELD, name, "authorizationInfo", aiFieldDesc)
    mv.visitInsn(Opcodes.ARETURN)
    mv.visitMaxs(1, 0)
    mv.visitEnd()

    // private static AuthorizationInfo rorCreateArtificialAuthorizationInfo() { ... per ES version ... }
    esVersion match {
      case v if v >= es830 => emitRorCreateArtificialAuthorizationInfoForEsGE830(cw)
      case v if v >= es820 => emitRorCreateArtificialAuthorizationInfoForEsGE820(cw)
      case v if v >= es810 => emitRorCreateArtificialAuthorizationInfoForEsGE810(cw)
      case v if v >= es800 => emitRorCreateArtificialAuthorizationInfoForEsGE800(cw)
      case v if v >= es670 => emitRorCreateArtificialAuthorizationInfoForEsGE670(cw)
      case _ => emitRorCreateArtificialAuthorizationInfoStub(cw) // return null
    }

    cw.visitEnd()
    cw.toByteArray
  }

  private def emitRorCreateArtificialAuthorizationInfoStub(cv: ClassVisitor): Unit = {
    val mv = cv.visitMethod(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
      "rorCreateArtificialAuthorizationInfo",
      "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
      null,
      null
    )
    mv.visitCode()
    mv.visitInsn(Opcodes.ACONST_NULL)
    mv.visitInsn(Opcodes.ARETURN)
    mv.visitMaxs(1, 0)
    mv.visitEnd()
  }

  // --- the per-version emitters below are unchanged ---

  private def emitRorCreateArtificialAuthorizationInfoForEsGE830(cv: ClassVisitor): Unit = {
    val mv = cv.visitMethod(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
      "rorCreateArtificialAuthorizationInfo",
      "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
      null,
      null
    )
    mv.visitCode()

    mv.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authz/RestrictedIndices")
    mv.visitInsn(Opcodes.DUP)
    mv.visitFieldInsn(
      Opcodes.GETSTATIC,
      "org/elasticsearch/xpack/core/security/support/Automatons",
      "EMPTY",
      "Lorg/apache/lucene/util/automaton/Automaton;"
    )
    mv.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "org/elasticsearch/xpack/core/security/authz/RestrictedIndices",
      "<init>",
      "(Lorg/apache/lucene/util/automaton/Automaton;)V",
      false
    )
    mv.visitVarInsn(Opcodes.ASTORE, 0)

    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitInsn(Opcodes.DUP)
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitLdcInsn("superuser")
    mv.visitInsn(Opcodes.AASTORE)
    mv.visitMethodInsn(
      Opcodes.INVOKESTATIC,
      "org/elasticsearch/xpack/core/security/authz/permission/Role",
      "builder",
      "(Lorg/elasticsearch/xpack/core/security/authz/RestrictedIndices;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      true
    )

    mv.visitLdcInsn("all")
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "cluster",
      "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitFieldInsn(
      Opcodes.GETSTATIC,
      "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege",
      "ALL",
      "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;"
    )
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitInsn(Opcodes.DUP)
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitLdcInsn("*")
    mv.visitInsn(Opcodes.AASTORE)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "add",
      "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "build",
      "()Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;",
      false
    )
    mv.visitVarInsn(Opcodes.ASTORE, 1)

    mv.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo")
    mv.visitInsn(Opcodes.DUP)
    mv.visitVarInsn(Opcodes.ALOAD, 1)
    mv.visitVarInsn(Opcodes.ALOAD, 1)
    mv.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo",
      "<init>",
      "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V",
      false
    )
    mv.visitInsn(Opcodes.ARETURN)

    mv.visitMaxs(6, 2)
    mv.visitEnd()
  }

  private def emitRorCreateArtificialAuthorizationInfoForEsGE820(cv: ClassVisitor): Unit = {
    val mv = cv.visitMethod(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
      "rorCreateArtificialAuthorizationInfo",
      "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
      null,
      null
    )
    mv.visitCode()

    mv.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authz/RestrictedIndices")
    mv.visitInsn(Opcodes.DUP)
    mv.visitFieldInsn(
      Opcodes.GETSTATIC,
      "org/elasticsearch/xpack/core/security/support/Automatons",
      "EMPTY",
      "Lorg/apache/lucene/util/automaton/Automaton;"
    )
    mv.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "org/elasticsearch/xpack/core/security/authz/RestrictedIndices",
      "<init>",
      "(Lorg/apache/lucene/util/automaton/Automaton;)V",
      false
    )
    mv.visitVarInsn(Opcodes.ASTORE, 0)

    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitInsn(Opcodes.DUP)
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitLdcInsn("superuser")
    mv.visitInsn(Opcodes.AASTORE)
    mv.visitMethodInsn(
      Opcodes.INVOKESTATIC,
      "org/elasticsearch/xpack/core/security/authz/permission/Role",
      "builder",
      "(Lorg/elasticsearch/xpack/core/security/authz/RestrictedIndices;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      true
    )

    mv.visitLdcInsn("all")
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "cluster",
      "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitFieldInsn(
      Opcodes.GETSTATIC,
      "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege",
      "ALL",
      "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;"
    )
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitInsn(Opcodes.DUP)
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitLdcInsn("*")
    mv.visitInsn(Opcodes.AASTORE)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "add",
      "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege")
    mv.visitInsn(Opcodes.DUP)
    mv.visitLdcInsn("*")
    mv.visitLdcInsn("*")
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege",
      "<init>",
      "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V",
      false
    )
    mv.visitLdcInsn("*")
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "addApplicationPrivilege",
      "(Lorg/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege;Ljava/util/Set;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "build",
      "()Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;",
      false
    )
    mv.visitVarInsn(Opcodes.ASTORE, 1)

    mv.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo")
    mv.visitInsn(Opcodes.DUP)
    mv.visitVarInsn(Opcodes.ALOAD, 1)
    mv.visitVarInsn(Opcodes.ALOAD, 1)
    mv.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo",
      "<init>",
      "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V",
      false
    )
    mv.visitInsn(Opcodes.ARETURN)

    mv.visitMaxs(6, 2)
    mv.visitEnd()
  }

  private def emitRorCreateArtificialAuthorizationInfoForEsGE810(cv: ClassVisitor): Unit = {
    val mv = cv.visitMethod(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
      "rorCreateArtificialAuthorizationInfo",
      "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
      null,
      null
    )
    mv.visitCode()

    mv.visitFieldInsn(
      Opcodes.GETSTATIC,
      "org/elasticsearch/xpack/core/security/support/Automatons",
      "EMPTY",
      "Lorg/apache/lucene/util/automaton/Automaton;"
    )
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitInsn(Opcodes.DUP)
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitLdcInsn("superuser")
    mv.visitInsn(Opcodes.AASTORE)
    mv.visitMethodInsn(
      Opcodes.INVOKESTATIC,
      "org/elasticsearch/xpack/core/security/authz/permission/Role",
      "builder",
      "(Lorg/apache/lucene/util/automaton/Automaton;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      true
    )

    mv.visitLdcInsn("all")
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "cluster",
      "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitFieldInsn(
      Opcodes.GETSTATIC,
      "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege",
      "ALL",
      "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;"
    )
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitInsn(Opcodes.DUP)
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitLdcInsn("*")
    mv.visitInsn(Opcodes.AASTORE)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "add",
      "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege")
    mv.visitInsn(Opcodes.DUP)
    mv.visitLdcInsn("*")
    mv.visitLdcInsn("*")
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege",
      "<init>",
      "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V",
      false
    )
    mv.visitLdcInsn("*")
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "addApplicationPrivilege",
      "(Lorg/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege;Ljava/util/Set;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "build",
      "()Lorg/elasticsearch/xpack/core/security/authz/permission/SimpleRole;",
      false
    )
    mv.visitVarInsn(Opcodes.ASTORE, 0)

    mv.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo")
    mv.visitInsn(Opcodes.DUP)
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo",
      "<init>",
      "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V",
      false
    )
    mv.visitInsn(Opcodes.ARETURN)

    mv.visitMaxs(6, 1)
    mv.visitEnd()
  }

  private def emitRorCreateArtificialAuthorizationInfoForEsGE800(cv: ClassVisitor): Unit = {
    val mv = cv.visitMethod(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
      "rorCreateArtificialAuthorizationInfo",
      "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
      null,
      null
    )
    mv.visitCode()

    mv.visitFieldInsn(
      Opcodes.GETSTATIC,
      "org/elasticsearch/xpack/core/security/support/Automatons",
      "EMPTY",
      "Lorg/apache/lucene/util/automaton/Automaton;"
    )
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitInsn(Opcodes.DUP)
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitLdcInsn("superuser")
    mv.visitInsn(Opcodes.AASTORE)
    mv.visitMethodInsn(
      Opcodes.INVOKESTATIC,
      "org/elasticsearch/xpack/core/security/authz/permission/Role",
      "builder",
      "(Lorg/apache/lucene/util/automaton/Automaton;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitLdcInsn("all")
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "cluster",
      "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitFieldInsn(
      Opcodes.GETSTATIC,
      "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege",
      "ALL",
      "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;"
    )
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitInsn(Opcodes.DUP)
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitLdcInsn("*")
    mv.visitInsn(Opcodes.AASTORE)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "add",
      "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege")
    mv.visitInsn(Opcodes.DUP)
    mv.visitLdcInsn("*")
    mv.visitLdcInsn("*")
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege",
      "<init>",
      "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V",
      false
    )
    mv.visitLdcInsn("*")
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "addApplicationPrivilege",
      "(Lorg/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege;Ljava/util/Set;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "build",
      "()Lorg/elasticsearch/xpack/core/security/authz/permission/Role;",
      false
    )
    mv.visitVarInsn(Opcodes.ASTORE, 0)

    mv.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo")
    mv.visitInsn(Opcodes.DUP)
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo",
      "<init>",
      "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V",
      false
    )
    mv.visitInsn(Opcodes.ARETURN)

    mv.visitMaxs(6, 1)
    mv.visitEnd()
  }

  private def emitRorCreateArtificialAuthorizationInfoForEsGE670(cv: ClassVisitor): Unit = {
    val mv = cv.visitMethod(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
      "rorCreateArtificialAuthorizationInfo",
      "()Lorg/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizationInfo;",
      null,
      null
    )
    mv.visitCode()

    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitInsn(Opcodes.DUP)
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitLdcInsn("superuser")
    mv.visitInsn(Opcodes.AASTORE)
    mv.visitMethodInsn(
      Opcodes.INVOKESTATIC,
      "org/elasticsearch/xpack/core/security/authz/permission/Role",
      "builder",
      "([Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitLdcInsn("all")
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "cluster",
      "(Ljava/util/Set;Ljava/lang/Iterable;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitFieldInsn(
      Opcodes.GETSTATIC,
      "org/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege",
      "ALL",
      "Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;"
    )
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitInsn(Opcodes.DUP)
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitLdcInsn("*")
    mv.visitInsn(Opcodes.AASTORE)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "add",
      "(Lorg/elasticsearch/xpack/core/security/authz/privilege/IndexPrivilege;[Ljava/lang/String;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege")
    mv.visitInsn(Opcodes.DUP)
    mv.visitLdcInsn("*")
    mv.visitLdcInsn("*")
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
    mv.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "org/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege",
      "<init>",
      "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V",
      false
    )
    mv.visitLdcInsn("*")
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "addApplicationPrivilege",
      "(Lorg/elasticsearch/xpack/core/security/authz/privilege/ApplicationPrivilege;Ljava/util/Set;)Lorg/elasticsearch/xpack/core/security/authz/permission/Role$Builder;",
      false
    )

    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/xpack/core/security/authz/permission/Role$Builder",
      "build",
      "()Lorg/elasticsearch/xpack/core/security/authz/permission/Role;",
      false
    )
    mv.visitVarInsn(Opcodes.ASTORE, 0)

    mv.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo")
    mv.visitInsn(Opcodes.DUP)
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      "org/elasticsearch/xpack/security/authz/RBACEngine$RBACAuthorizationInfo",
      "<init>",
      "(Lorg/elasticsearch/xpack/core/security/authz/permission/Role;Lorg/elasticsearch/xpack/core/security/authz/permission/Role;)V",
      false
    )
    mv.visitInsn(Opcodes.ARETURN)

    mv.visitMaxs(6, 1)
    mv.visitEnd()
  }
}

object CreateRorAuthorizationInfoProviderClass {
  def apply(esVersion: SemVer): CreateRorAuthorizationInfoProviderClass = new CreateRorAuthorizationInfoProviderClass(esVersion)
}
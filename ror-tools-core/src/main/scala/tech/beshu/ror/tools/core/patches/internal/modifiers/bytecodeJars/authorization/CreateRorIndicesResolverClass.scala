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

import just.semver.SemVer
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier
import tech.beshu.ror.tools.core.utils.EsUtil.{es910, es930}

import java.io.File

/**
 * Adds two new classes to the xpack security JAR:
 *
 * 1. `org.elasticsearch.xpack.security.authz.RorIndicesResolver`
 *    – static helper that resolves raw index expressions (e.g. `*:*`) on
 *      `IndicesRequest.Replaceable` requests, replicating the side‑effect
 *      that the original (non‑patched) `AuthorizationService.authorize()`
 *      performs via `IndicesAndAliasesResolver.resolve()`.
 *
 * 2. `org.elasticsearch.xpack.security.authz.RorIndicesResolver$AllIndicesAuthorized`
 *    – inner class implementing `AuthorizationEngine.AuthorizedIndices` that
 *      authorizes every index (returns all cluster indices from metadata).
 *
 * Version-specific handling:
 *  - ES 8.19.x–9.0.x: resolve(action, request, Metadata, AuthorizedIndices)
 *  - ES 9.1.x–9.2.x:  resolve(action, request, ProjectMetadata, AuthorizedIndices)
 *  - ES 9.3.x+:        resolve(action, request, ProjectMetadata, AuthorizedIndices, TargetProjects)
 */
private[patches] class CreateRorIndicesResolverClass(esVersion: SemVer)
  extends BytecodeJarModifier {

  private val outerName = "org/elasticsearch/xpack/security/authz/RorIndicesResolver"
  private val innerName = s"$outerName$$AllIndicesAuthorized"

  private val authorizedIndicesIface =
    "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizedIndices"
  private val metadataClass = "org/elasticsearch/cluster/metadata/Metadata"
  private val metadataDesc = s"L$metadataClass;"
  private val projectMetadataClass = "org/elasticsearch/cluster/metadata/ProjectMetadata"
  private val projectMetadataDesc = s"L$projectMetadataClass;"
  private val clusterServiceClass = "org/elasticsearch/cluster/service/ClusterService"
  private val resolverClass = "org/elasticsearch/xpack/security/authz/IndicesAndAliasesResolver"
  private val transportRequestClass = "org/elasticsearch/transport/TransportRequest"
  private val replaceableClass = "org/elasticsearch/action/IndicesRequest$Replaceable"
  private val indexComponentSelectorDesc = "Lorg/elasticsearch/action/support/IndexComponentSelector;"
  private val targetProjectsClass = "org/elasticsearch/search/crossproject/TargetProjects"

  private val usesProjectMetadata: Boolean = esVersion >= es910
  private val usesTargetProjects: Boolean = esVersion >= es930

  // The metadata type used for the inner class field and the resolve() call
  private val effectiveMetadataClass: String = if (usesProjectMetadata) projectMetadataClass else metadataClass
  private val effectiveMetadataDesc: String = if (usesProjectMetadata) projectMetadataDesc else metadataDesc

  override def apply(jar: File): Unit = {
    addNewFileToJar(
      jar = jar,
      filePathString = s"$outerName.class",
      content = generateOuterClass()
    )
    addNewFileToJar(
      jar = jar,
      filePathString = s"$innerName.class",
      content = generateInnerClass()
    )
  }

  // ---------------------------------------------------------------------------
  //  Outer class: RorIndicesResolver
  // ---------------------------------------------------------------------------
  private def generateOuterClass(): Array[Byte] = {
    val cw = new ClassWriter(0)

    cw.visit(
      Opcodes.V1_8,
      Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
      outerName,
      null,
      "java/lang/Object",
      null
    )

    // Register the inner class relationship
    cw.visitInnerClass(
      innerName,
      outerName,
      "AllIndicesAuthorized",
      Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
    )

    // Also register the IndicesRequest$Replaceable inner class
    cw.visitInnerClass(
      replaceableClass,
      "org/elasticsearch/action/IndicesRequest",
      "Replaceable",
      Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE
    )

    // Also register AuthorizationEngine$AuthorizedIndices
    cw.visitInnerClass(
      authorizedIndicesIface,
      "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine",
      "AuthorizedIndices",
      Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE
    )

    // private <init>() — never instantiated
    emitPrivateConstructor(cw, outerName)

    // public static void resolveIndices(String, TransportRequest, ClusterService, IndicesAndAliasesResolver)
    emitResolveIndicesMethod(cw)

    cw.visitEnd()
    cw.toByteArray
  }

  private def emitResolveIndicesMethod(cw: ClassWriter): Unit = {
    val desc = s"(Ljava/lang/String;L$transportRequestClass;L$clusterServiceClass;L$resolverClass;)V"
    val mv = cw.visitMethod(
      Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
      "resolveIndices",
      desc,
      null,
      null
    )
    mv.visitCode()

    // Slot mapping:  0=action, 1=request, 2=clusterService, 3=resolver

    val labelStart = new Label()
    val labelReturn = new Label()
    val labelTryStart = new Label()
    val labelTryEnd = new Label()
    val labelCatchHandler = new Label()

    // Exception table must be registered before the labels are visited
    mv.visitTryCatchBlock(labelTryStart, labelTryEnd, labelCatchHandler, "java/lang/Exception")

    mv.visitLabel(labelStart)

    // if (!(request instanceof IndicesRequest.Replaceable)) return;
    mv.visitVarInsn(Opcodes.ALOAD, 1) // request
    mv.visitTypeInsn(Opcodes.INSTANCEOF, replaceableClass)
    mv.visitJumpInsn(Opcodes.IFEQ, labelReturn)

    // -- try {
    mv.visitLabel(labelTryStart)

    // Metadata metadata = clusterService.state().metadata();
    mv.visitVarInsn(Opcodes.ALOAD, 2) // clusterService
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      clusterServiceClass,
      "state",
      "()Lorg/elasticsearch/cluster/ClusterState;",
      false
    )
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "org/elasticsearch/cluster/ClusterState",
      "metadata",
      s"()$metadataDesc",
      false
    )

    if (usesProjectMetadata) {
      // ProjectMetadata pm = metadata.getProject();
      mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        metadataClass,
        "getProject",
        s"()$projectMetadataDesc",
        false
      )
    }

    mv.visitVarInsn(Opcodes.ASTORE, 4) // metadata/projectMetadata -> slot 4

    // AllIndicesAuthorized auth = new AllIndicesAuthorized(metadata/projectMetadata);
    mv.visitTypeInsn(Opcodes.NEW, innerName)
    mv.visitInsn(Opcodes.DUP)
    mv.visitVarInsn(Opcodes.ALOAD, 4)
    mv.visitMethodInsn(
      Opcodes.INVOKESPECIAL,
      innerName,
      "<init>",
      s"($effectiveMetadataDesc)V",
      false
    )
    mv.visitVarInsn(Opcodes.ASTORE, 5) // auth -> slot 5

    // resolver.resolve(action, request, metadata/projectMetadata, auth [, targetProjects]);
    mv.visitVarInsn(Opcodes.ALOAD, 3) // resolver
    mv.visitVarInsn(Opcodes.ALOAD, 0) // action
    mv.visitVarInsn(Opcodes.ALOAD, 1) // request
    mv.visitVarInsn(Opcodes.ALOAD, 4) // metadata/projectMetadata
    mv.visitVarInsn(Opcodes.ALOAD, 5) // auth

    if (usesTargetProjects) {
      // TargetProjects.LOCAL_ONLY_FOR_CPS_DISABLED
      mv.visitFieldInsn(
        Opcodes.GETSTATIC,
        targetProjectsClass,
        "LOCAL_ONLY_FOR_CPS_DISABLED",
        s"L$targetProjectsClass;"
      )
      mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        resolverClass,
        "resolve",
        s"(Ljava/lang/String;L$transportRequestClass;${effectiveMetadataDesc}L$authorizedIndicesIface;L$targetProjectsClass;)" +
          "Lorg/elasticsearch/xpack/core/security/authz/ResolvedIndices;",
        false
      )
    } else {
      mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        resolverClass,
        "resolve",
        s"(Ljava/lang/String;L$transportRequestClass;${effectiveMetadataDesc}L$authorizedIndicesIface;)" +
          "Lorg/elasticsearch/xpack/core/security/authz/ResolvedIndices;",
        false
      )
    }

    mv.visitInsn(Opcodes.POP) // discard ResolvedIndices return value

    mv.visitLabel(labelTryEnd)
    mv.visitJumpInsn(Opcodes.GOTO, labelReturn)

    // -- } catch (Exception e) { /* swallow */ }
    mv.visitLabel(labelCatchHandler)
    mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, Array[AnyRef]("java/lang/Exception"))
    mv.visitVarInsn(Opcodes.ASTORE, 4) // store exception, ignore it
    // fall through to return

    // return;
    mv.visitLabel(labelReturn)
    mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
    mv.visitInsn(Opcodes.RETURN)

    // Local variable bookkeeping
    val labelEnd = new Label()
    mv.visitLabel(labelEnd)
    mv.visitLocalVariable("action", "Ljava/lang/String;", null, labelStart, labelEnd, 0)
    mv.visitLocalVariable("request", s"L$transportRequestClass;", null, labelStart, labelEnd, 1)
    mv.visitLocalVariable("clusterService", s"L$clusterServiceClass;", null, labelStart, labelEnd, 2)
    mv.visitLocalVariable("resolver", s"L$resolverClass;", null, labelStart, labelEnd, 3)

    val maxStack = if (usesTargetProjects) 6 else 5
    mv.visitMaxs(maxStack, 6)
    mv.visitEnd()
  }

  // ---------------------------------------------------------------------------
  //  Inner class: RorIndicesResolver$AllIndicesAuthorized
  // ---------------------------------------------------------------------------
  private def generateInnerClass(): Array[Byte] = {
    val cw = new ClassWriter(0)

    cw.visit(
      Opcodes.V1_8,
      Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
      innerName,
      null,
      "java/lang/Object",
      Array(authorizedIndicesIface)
    )

    // Register inner class relationship
    cw.visitInnerClass(
      innerName,
      outerName,
      "AllIndicesAuthorized",
      Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
    )

    // Register AuthorizationEngine$AuthorizedIndices
    cw.visitInnerClass(
      authorizedIndicesIface,
      "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine",
      "AuthorizedIndices",
      Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE
    )

    // private final Metadata/ProjectMetadata metadata;
    cw.visitField(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
      "metadata",
      effectiveMetadataDesc,
      null,
      null
    ).visitEnd()

    // <init>(Metadata/ProjectMetadata metadata)
    emitInnerConstructor(cw)

    // public Set<String> all(IndexComponentSelector selector)
    emitAllMethod(cw)

    // public boolean check(String name, IndexComponentSelector selector)
    emitCheckMethod(cw)

    cw.visitEnd()
    cw.toByteArray
  }

  private def emitInnerConstructor(cw: ClassWriter): Unit = {
    val mv = cw.visitMethod(
      Opcodes.ACC_PUBLIC,
      "<init>",
      s"($effectiveMetadataDesc)V",
      null,
      null
    )
    mv.visitCode()

    // super()
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

    // this.metadata = metadata
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitVarInsn(Opcodes.ALOAD, 1)
    mv.visitFieldInsn(Opcodes.PUTFIELD, innerName, "metadata", effectiveMetadataDesc)

    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(2, 2)
    mv.visitEnd()
  }

  private def emitAllMethod(cw: ClassWriter): Unit = {
    // public Set<String> all(IndexComponentSelector selector)
    val mv = cw.visitMethod(
      Opcodes.ACC_PUBLIC,
      "all",
      s"($indexComponentSelectorDesc)Ljava/util/Set;",
      null,
      null
    )
    mv.visitCode()

    // return this.metadata.getIndicesLookup().keySet();
    mv.visitVarInsn(Opcodes.ALOAD, 0) // this
    mv.visitFieldInsn(Opcodes.GETFIELD, innerName, "metadata", effectiveMetadataDesc)
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      effectiveMetadataClass,
      "getIndicesLookup",
      "()Ljava/util/SortedMap;",
      false
    )
    mv.visitMethodInsn(
      Opcodes.INVOKEINTERFACE,
      "java/util/SortedMap",
      "keySet",
      "()Ljava/util/Set;",
      true
    )
    mv.visitInsn(Opcodes.ARETURN)

    mv.visitMaxs(1, 2)
    mv.visitEnd()
  }

  private def emitCheckMethod(cw: ClassWriter): Unit = {
    // public boolean check(String name, IndexComponentSelector selector)
    val mv = cw.visitMethod(
      Opcodes.ACC_PUBLIC,
      "check",
      s"(Ljava/lang/String;$indexComponentSelectorDesc)Z",
      null,
      null
    )
    mv.visitCode()

    // return true;
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitInsn(Opcodes.IRETURN)

    mv.visitMaxs(1, 3)
    mv.visitEnd()
  }

  // ---------------------------------------------------------------------------
  //  Shared helpers
  // ---------------------------------------------------------------------------
  private def emitPrivateConstructor(cw: ClassWriter, className: String): Unit = {
    val mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
    mv.visitCode()
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 1)
    mv.visitEnd()
  }
}

object CreateRorIndicesResolverClass {
  def apply(esVersion: SemVer): CreateRorIndicesResolverClass = new CreateRorIndicesResolverClass(esVersion)
}

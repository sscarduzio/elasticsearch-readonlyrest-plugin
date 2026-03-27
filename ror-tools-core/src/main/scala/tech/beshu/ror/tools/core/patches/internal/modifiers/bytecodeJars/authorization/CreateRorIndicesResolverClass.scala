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

import better.files.*
import just.semver.SemVer
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier
import tech.beshu.ror.tools.core.utils.EsUtil.{es8180, es8190, es910, es930}

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
 *  - ES 8.18.x:        resolve(Metadata, AuthorizedIndices{all()->Supplier, check(String)})
 *  - ES 8.19.x–9.0.x:  resolve(Metadata, AuthorizedIndices{all(Selector), check(String,Selector)})
 *  - ES 9.1.x–9.2.x:   resolve(ProjectMetadata, AuthorizedIndices{all(Selector), check(String,Selector)})
 *  - ES 9.3.x+:        resolve(ProjectMetadata, AuthorizedIndices, TargetProjects)
 */
private[patches] class CreateRorIndicesResolverClass(esVersion: SemVer)
  extends BytecodeJarModifier {

  private val outerName = "org/elasticsearch/xpack/security/authz/RorIndicesResolver"
  private val innerName = s"$outerName$$AllIndicesAuthorized"

  private val authorizedIndicesIface =
    "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine$AuthorizedIndices"
  private val metadataClass         = "org/elasticsearch/cluster/metadata/Metadata"
  private val metadataDesc          = s"L$metadataClass;"
  private val projectMetadataClass  = "org/elasticsearch/cluster/metadata/ProjectMetadata"
  private val projectMetadataDesc   = s"L$projectMetadataClass;"
  private val clusterServiceClass   = "org/elasticsearch/cluster/service/ClusterService"
  private val resolverClass         = "org/elasticsearch/xpack/security/authz/IndicesAndAliasesResolver"
  private val transportRequestClass = "org/elasticsearch/transport/TransportRequest"
  private val replaceableClass      = "org/elasticsearch/action/IndicesRequest$Replaceable"
  private val indexComponentSelectorDesc = "Lorg/elasticsearch/action/support/IndexComponentSelector;"
  private val targetProjectsClass   = "org/elasticsearch/search/crossproject/TargetProjects"

  override def apply(jar: File): Unit = {
    val generator: VersionedGenerator = esVersion match {
      case v if v >= es930  => new GeneratorForEs930AndAbove()
      case v if v >= es910  => new GeneratorForEs910To929()
      case v if v >= es8190 => new GeneratorForEs819To909()
      case v if v >= es8180 => new GeneratorForEs818()
      case _ => throw new IllegalArgumentException(
        s"${CreateRorIndicesResolverClass.getClass.getSimpleName} does not support ES version $esVersion (requires ES 8.18.0 or newer)"
      )
    }
    addNewFileToJar(jar = jar, filePathString = s"$outerName.class", content = generator.generateOuter())
    addNewFileToJar(jar = jar, filePathString = s"$innerName.class", content = generator.generateInner())
  }

  private sealed trait VersionedGenerator {
    def generateOuter(): Array[Byte]
    def generateInner(): Array[Byte]
  }

  private class GeneratorForEs818 extends VersionedGenerator {

    override def generateOuter(): Array[Byte] = {
      val cw = new ClassWriter(0)

      cw.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        outerName, null, "java/lang/Object", null
      )
      cw.visitInnerClass(innerName, outerName, "AllIndicesAuthorized",
        Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
      cw.visitInnerClass(replaceableClass, "org/elasticsearch/action/IndicesRequest", "Replaceable",
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)
      cw.visitInnerClass(authorizedIndicesIface, "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine", "AuthorizedIndices",
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)

      // private <init>() — never instantiated
      {
        val mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
      }

      // public static void resolveIndices(String, TransportRequest, ClusterService, IndicesAndAliasesResolver)
      {
        val desc = s"(Ljava/lang/String;L$transportRequestClass;L$clusterServiceClass;L$resolverClass;)V"
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "resolveIndices", desc, null, null)
        mv.visitCode()

        val labelStart      = new Label()
        val labelReturn     = new Label()
        val labelTryStart   = new Label()
        val labelTryEnd     = new Label()
        val labelCatch      = new Label()

        mv.visitTryCatchBlock(labelTryStart, labelTryEnd, labelCatch, "java/lang/Exception")
        mv.visitLabel(labelStart)

        // if (!(request instanceof IndicesRequest.Replaceable)) return;
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, replaceableClass)
        mv.visitJumpInsn(Opcodes.IFEQ, labelReturn)

        mv.visitLabel(labelTryStart)

        // Metadata metadata = clusterService.state().metadata()
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, clusterServiceClass, "state",
          "()Lorg/elasticsearch/cluster/ClusterState;", false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/cluster/ClusterState", "metadata",
          s"()$metadataDesc", false)
        mv.visitVarInsn(Opcodes.ASTORE, 4)

        // AllIndicesAuthorized auth = new AllIndicesAuthorized(metadata)
        mv.visitTypeInsn(Opcodes.NEW, innerName)
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, innerName, "<init>", s"($metadataDesc)V", false)
        mv.visitVarInsn(Opcodes.ASTORE, 5)

        // resolver.resolve(action, request, metadata, auth)
        mv.visitVarInsn(Opcodes.ALOAD, 3)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitVarInsn(Opcodes.ALOAD, 5)
        mv.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL, resolverClass, "resolve",
          s"(Ljava/lang/String;L$transportRequestClass;${metadataDesc}L$authorizedIndicesIface;)" +
            "Lorg/elasticsearch/xpack/core/security/authz/ResolvedIndices;",
          false
        )
        mv.visitInsn(Opcodes.POP)

        mv.visitLabel(labelTryEnd)
        mv.visitJumpInsn(Opcodes.GOTO, labelReturn)

        mv.visitLabel(labelCatch)
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, Array[AnyRef]("java/lang/Exception"))
        mv.visitVarInsn(Opcodes.ASTORE, 4)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitInsn(Opcodes.ATHROW)

        mv.visitLabel(labelReturn)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitInsn(Opcodes.RETURN)

        val labelEnd = new Label()
        mv.visitLabel(labelEnd)
        mv.visitLocalVariable("action",         "Ljava/lang/String;",       null, labelStart, labelEnd, 0)
        mv.visitLocalVariable("request",        s"L$transportRequestClass;", null, labelStart, labelEnd, 1)
        mv.visitLocalVariable("clusterService", s"L$clusterServiceClass;",   null, labelStart, labelEnd, 2)
        mv.visitLocalVariable("resolver",       s"L$resolverClass;",         null, labelStart, labelEnd, 3)

        mv.visitMaxs(5, 6)
        mv.visitEnd()
      }

      cw.visitEnd()
      cw.toByteArray
    }

    override def generateInner(): Array[Byte] = {
      val cw = new ClassWriter(0)

      cw.visit(
        Opcodes.V1_8,
        Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        innerName, null, "java/lang/Object", Array(authorizedIndicesIface)
      )
      cw.visitInnerClass(innerName, outerName, "AllIndicesAuthorized",
        Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
      cw.visitInnerClass(authorizedIndicesIface, "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine", "AuthorizedIndices",
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)

      cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "metadata", metadataDesc, null, null).visitEnd()

      // <init>(Metadata metadata)
      {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", s"($metadataDesc)V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitFieldInsn(Opcodes.PUTFIELD, innerName, "metadata", metadataDesc)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(2, 2)
        mv.visitEnd()
      }

      // private static Set lambda$all$0(Set s) { return s; }
      {
        val mv = cw.visitMethod(
          Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
          "lambda$all$0", "(Ljava/util/Set;)Ljava/util/Set;", null, null
        )
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
      }

      // public Supplier<Set<String>> all()
      {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "all", "()Ljava/util/function/Supplier;", null, null)
        mv.visitCode()

        // Set<String> set = this.metadata.getIndicesLookup().keySet()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, innerName, "metadata", metadataDesc)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, metadataClass, "getIndicesLookup", "()Ljava/util/SortedMap;", false)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/SortedMap", "keySet", "()Ljava/util/Set;", true)
        mv.visitVarInsn(Opcodes.ASTORE, 1)

        // return () -> set;  (via invokedynamic capturing the set)
        val bootstrapHandle = new Handle(
          Opcodes.H_INVOKESTATIC,
          "java/lang/invoke/LambdaMetafactory",
          "metafactory",
          "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
          false
        )
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInvokeDynamicInsn(
          "get",
          "(Ljava/util/Set;)Ljava/util/function/Supplier;",
          bootstrapHandle,
          Array[AnyRef](
            org.objectweb.asm.Type.getType("()Ljava/lang/Object;"),
            new Handle(Opcodes.H_INVOKESTATIC, innerName, "lambda$all$0", "(Ljava/util/Set;)Ljava/util/Set;", false),
            org.objectweb.asm.Type.getType("()Ljava/util/Set;")
          ): _*
        )
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(2, 2)
        mv.visitEnd()
      }

      // public boolean check(String name)
      {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "check", "(Ljava/lang/String;)Z", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 2)
        mv.visitEnd()
      }

      cw.visitEnd()
      cw.toByteArray
    }
  }

  private class GeneratorForEs819To909 extends VersionedGenerator {

    override def generateOuter(): Array[Byte] = {
      val cw = new ClassWriter(0)

      cw.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        outerName, null, "java/lang/Object", null
      )
      cw.visitInnerClass(innerName, outerName, "AllIndicesAuthorized",
        Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
      cw.visitInnerClass(replaceableClass, "org/elasticsearch/action/IndicesRequest", "Replaceable",
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)
      cw.visitInnerClass(authorizedIndicesIface, "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine", "AuthorizedIndices",
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)

      // private <init>() — never instantiated
      {
        val mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
      }

      // public static void resolveIndices(String, TransportRequest, ClusterService, IndicesAndAliasesResolver)
      {
        val desc = s"(Ljava/lang/String;L$transportRequestClass;L$clusterServiceClass;L$resolverClass;)V"
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "resolveIndices", desc, null, null)
        mv.visitCode()

        val labelStart    = new Label()
        val labelReturn   = new Label()
        val labelTryStart = new Label()
        val labelTryEnd   = new Label()
        val labelCatch    = new Label()

        mv.visitTryCatchBlock(labelTryStart, labelTryEnd, labelCatch, "java/lang/Exception")
        mv.visitLabel(labelStart)

        // if (!(request instanceof IndicesRequest.Replaceable)) return;
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, replaceableClass)
        mv.visitJumpInsn(Opcodes.IFEQ, labelReturn)

        mv.visitLabel(labelTryStart)

        // Metadata metadata = clusterService.state().metadata()
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, clusterServiceClass, "state",
          "()Lorg/elasticsearch/cluster/ClusterState;", false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/cluster/ClusterState", "metadata",
          s"()$metadataDesc", false)
        mv.visitVarInsn(Opcodes.ASTORE, 4)

        // AllIndicesAuthorized auth = new AllIndicesAuthorized(metadata)
        mv.visitTypeInsn(Opcodes.NEW, innerName)
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, innerName, "<init>", s"($metadataDesc)V", false)
        mv.visitVarInsn(Opcodes.ASTORE, 5)

        // resolver.resolve(action, request, metadata, auth)
        mv.visitVarInsn(Opcodes.ALOAD, 3)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitVarInsn(Opcodes.ALOAD, 5)
        mv.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL, resolverClass, "resolve",
          s"(Ljava/lang/String;L$transportRequestClass;${metadataDesc}L$authorizedIndicesIface;)" +
            "Lorg/elasticsearch/xpack/core/security/authz/ResolvedIndices;",
          false
        )
        mv.visitInsn(Opcodes.POP)

        mv.visitLabel(labelTryEnd)
        mv.visitJumpInsn(Opcodes.GOTO, labelReturn)

        mv.visitLabel(labelCatch)
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, Array[AnyRef]("java/lang/Exception"))
        mv.visitVarInsn(Opcodes.ASTORE, 4)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitInsn(Opcodes.ATHROW)

        mv.visitLabel(labelReturn)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitInsn(Opcodes.RETURN)

        val labelEnd = new Label()
        mv.visitLabel(labelEnd)
        mv.visitLocalVariable("action",         "Ljava/lang/String;",       null, labelStart, labelEnd, 0)
        mv.visitLocalVariable("request",        s"L$transportRequestClass;", null, labelStart, labelEnd, 1)
        mv.visitLocalVariable("clusterService", s"L$clusterServiceClass;",   null, labelStart, labelEnd, 2)
        mv.visitLocalVariable("resolver",       s"L$resolverClass;",         null, labelStart, labelEnd, 3)

        mv.visitMaxs(5, 6)
        mv.visitEnd()
      }

      cw.visitEnd()
      cw.toByteArray
    }

    override def generateInner(): Array[Byte] = {
      val cw = new ClassWriter(0)

      cw.visit(
        Opcodes.V1_8,
        Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        innerName, null, "java/lang/Object", Array(authorizedIndicesIface)
      )
      cw.visitInnerClass(innerName, outerName, "AllIndicesAuthorized",
        Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
      cw.visitInnerClass(authorizedIndicesIface, "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine", "AuthorizedIndices",
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)

      cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "metadata", metadataDesc, null, null).visitEnd()

      // <init>(Metadata metadata)
      {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", s"($metadataDesc)V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitFieldInsn(Opcodes.PUTFIELD, innerName, "metadata", metadataDesc)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(2, 2)
        mv.visitEnd()
      }

      // public Set<String> all(IndexComponentSelector selector)
      {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "all", s"($indexComponentSelectorDesc)Ljava/util/Set;", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, innerName, "metadata", metadataDesc)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, metadataClass, "getIndicesLookup", "()Ljava/util/SortedMap;", false)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/SortedMap", "keySet", "()Ljava/util/Set;", true)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 2)
        mv.visitEnd()
      }

      // public boolean check(String name, IndexComponentSelector selector)
      {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "check", s"(Ljava/lang/String;$indexComponentSelectorDesc)Z", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 3)
        mv.visitEnd()
      }

      cw.visitEnd()
      cw.toByteArray
    }
  }

  private class GeneratorForEs910To929 extends VersionedGenerator {

    override def generateOuter(): Array[Byte] = {
      val cw = new ClassWriter(0)

      cw.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        outerName, null, "java/lang/Object", null
      )
      cw.visitInnerClass(innerName, outerName, "AllIndicesAuthorized",
        Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
      cw.visitInnerClass(replaceableClass, "org/elasticsearch/action/IndicesRequest", "Replaceable",
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)
      cw.visitInnerClass(authorizedIndicesIface, "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine", "AuthorizedIndices",
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)

      // private <init>() — never instantiated
      {
        val mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
      }

      // public static void resolveIndices(String, TransportRequest, ClusterService, IndicesAndAliasesResolver)
      {
        val desc = s"(Ljava/lang/String;L$transportRequestClass;L$clusterServiceClass;L$resolverClass;)V"
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "resolveIndices", desc, null, null)
        mv.visitCode()

        val labelStart    = new Label()
        val labelReturn   = new Label()
        val labelTryStart = new Label()
        val labelTryEnd   = new Label()
        val labelCatch    = new Label()

        mv.visitTryCatchBlock(labelTryStart, labelTryEnd, labelCatch, "java/lang/Exception")
        mv.visitLabel(labelStart)

        // if (!(request instanceof IndicesRequest.Replaceable)) return;
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, replaceableClass)
        mv.visitJumpInsn(Opcodes.IFEQ, labelReturn)

        mv.visitLabel(labelTryStart)

        // ProjectMetadata pm = clusterService.state().metadata().getProject()
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, clusterServiceClass, "state",
          "()Lorg/elasticsearch/cluster/ClusterState;", false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/cluster/ClusterState", "metadata",
          s"()$metadataDesc", false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, metadataClass, "getProject",
          s"()$projectMetadataDesc", false)
        mv.visitVarInsn(Opcodes.ASTORE, 4)

        // AllIndicesAuthorized auth = new AllIndicesAuthorized(pm)
        mv.visitTypeInsn(Opcodes.NEW, innerName)
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, innerName, "<init>", s"($projectMetadataDesc)V", false)
        mv.visitVarInsn(Opcodes.ASTORE, 5)

        // resolver.resolve(action, request, pm, auth)
        mv.visitVarInsn(Opcodes.ALOAD, 3)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitVarInsn(Opcodes.ALOAD, 5)
        mv.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL, resolverClass, "resolve",
          s"(Ljava/lang/String;L$transportRequestClass;${projectMetadataDesc}L$authorizedIndicesIface;)" +
            "Lorg/elasticsearch/xpack/core/security/authz/ResolvedIndices;",
          false
        )
        mv.visitInsn(Opcodes.POP)

        mv.visitLabel(labelTryEnd)
        mv.visitJumpInsn(Opcodes.GOTO, labelReturn)

        mv.visitLabel(labelCatch)
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, Array[AnyRef]("java/lang/Exception"))
        mv.visitVarInsn(Opcodes.ASTORE, 4)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitInsn(Opcodes.ATHROW)

        mv.visitLabel(labelReturn)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitInsn(Opcodes.RETURN)

        val labelEnd = new Label()
        mv.visitLabel(labelEnd)
        mv.visitLocalVariable("action",         "Ljava/lang/String;",       null, labelStart, labelEnd, 0)
        mv.visitLocalVariable("request",        s"L$transportRequestClass;", null, labelStart, labelEnd, 1)
        mv.visitLocalVariable("clusterService", s"L$clusterServiceClass;",   null, labelStart, labelEnd, 2)
        mv.visitLocalVariable("resolver",       s"L$resolverClass;",         null, labelStart, labelEnd, 3)

        mv.visitMaxs(5, 6)
        mv.visitEnd()
      }

      cw.visitEnd()
      cw.toByteArray
    }

    override def generateInner(): Array[Byte] = {
      val cw = new ClassWriter(0)

      cw.visit(
        Opcodes.V1_8,
        Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        innerName, null, "java/lang/Object", Array(authorizedIndicesIface)
      )
      cw.visitInnerClass(innerName, outerName, "AllIndicesAuthorized",
        Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
      cw.visitInnerClass(authorizedIndicesIface, "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine", "AuthorizedIndices",
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)

      cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "metadata", projectMetadataDesc, null, null).visitEnd()

      // <init>(ProjectMetadata metadata)
      {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", s"($projectMetadataDesc)V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitFieldInsn(Opcodes.PUTFIELD, innerName, "metadata", projectMetadataDesc)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(2, 2)
        mv.visitEnd()
      }

      // public Set<String> all(IndexComponentSelector selector)
      {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "all", s"($indexComponentSelectorDesc)Ljava/util/Set;", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, innerName, "metadata", projectMetadataDesc)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, projectMetadataClass, "getIndicesLookup", "()Ljava/util/SortedMap;", false)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/SortedMap", "keySet", "()Ljava/util/Set;", true)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 2)
        mv.visitEnd()
      }

      // public boolean check(String name, IndexComponentSelector selector)
      {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "check", s"(Ljava/lang/String;$indexComponentSelectorDesc)Z", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 3)
        mv.visitEnd()
      }

      cw.visitEnd()
      cw.toByteArray
    }
  }

  private class GeneratorForEs930AndAbove extends VersionedGenerator {

    override def generateOuter(): Array[Byte] = {
      val cw = new ClassWriter(0)

      cw.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        outerName, null, "java/lang/Object", null
      )
      cw.visitInnerClass(innerName, outerName, "AllIndicesAuthorized",
        Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
      cw.visitInnerClass(replaceableClass, "org/elasticsearch/action/IndicesRequest", "Replaceable",
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)
      cw.visitInnerClass(authorizedIndicesIface, "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine", "AuthorizedIndices",
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)

      // private <init>() — never instantiated
      {
        val mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
      }

      // public static void resolveIndices(String, TransportRequest, ClusterService, IndicesAndAliasesResolver)
      {
        val desc = s"(Ljava/lang/String;L$transportRequestClass;L$clusterServiceClass;L$resolverClass;)V"
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "resolveIndices", desc, null, null)
        mv.visitCode()

        val labelStart    = new Label()
        val labelReturn   = new Label()
        val labelTryStart = new Label()
        val labelTryEnd   = new Label()
        val labelCatch    = new Label()

        mv.visitTryCatchBlock(labelTryStart, labelTryEnd, labelCatch, "java/lang/Exception")
        mv.visitLabel(labelStart)

        // if (!(request instanceof IndicesRequest.Replaceable)) return;
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, replaceableClass)
        mv.visitJumpInsn(Opcodes.IFEQ, labelReturn)

        mv.visitLabel(labelTryStart)

        // ProjectMetadata pm = clusterService.state().metadata().getProject()
        mv.visitVarInsn(Opcodes.ALOAD, 2)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, clusterServiceClass, "state",
          "()Lorg/elasticsearch/cluster/ClusterState;", false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/cluster/ClusterState", "metadata",
          s"()$metadataDesc", false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, metadataClass, "getProject",
          s"()$projectMetadataDesc", false)
        mv.visitVarInsn(Opcodes.ASTORE, 4)

        // AllIndicesAuthorized auth = new AllIndicesAuthorized(pm)
        mv.visitTypeInsn(Opcodes.NEW, innerName)
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, innerName, "<init>", s"($projectMetadataDesc)V", false)
        mv.visitVarInsn(Opcodes.ASTORE, 5)

        // resolver.resolve(action, request, pm, auth, TargetProjects.LOCAL_ONLY_FOR_CPS_DISABLED)
        mv.visitVarInsn(Opcodes.ALOAD, 3)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitVarInsn(Opcodes.ALOAD, 5)
        mv.visitFieldInsn(Opcodes.GETSTATIC, targetProjectsClass, "LOCAL_ONLY_FOR_CPS_DISABLED", s"L$targetProjectsClass;")
        mv.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL, resolverClass, "resolve",
          s"(Ljava/lang/String;L$transportRequestClass;${projectMetadataDesc}L$authorizedIndicesIface;L$targetProjectsClass;)" +
            "Lorg/elasticsearch/xpack/core/security/authz/ResolvedIndices;",
          false
        )
        mv.visitInsn(Opcodes.POP)

        mv.visitLabel(labelTryEnd)
        mv.visitJumpInsn(Opcodes.GOTO, labelReturn)

        mv.visitLabel(labelCatch)
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, Array[AnyRef]("java/lang/Exception"))
        mv.visitVarInsn(Opcodes.ASTORE, 4)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitInsn(Opcodes.ATHROW)

        mv.visitLabel(labelReturn)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitInsn(Opcodes.RETURN)

        val labelEnd = new Label()
        mv.visitLabel(labelEnd)
        mv.visitLocalVariable("action",         "Ljava/lang/String;",       null, labelStart, labelEnd, 0)
        mv.visitLocalVariable("request",        s"L$transportRequestClass;", null, labelStart, labelEnd, 1)
        mv.visitLocalVariable("clusterService", s"L$clusterServiceClass;",   null, labelStart, labelEnd, 2)
        mv.visitLocalVariable("resolver",       s"L$resolverClass;",         null, labelStart, labelEnd, 3)

        mv.visitMaxs(6, 6)
        mv.visitEnd()
      }

      cw.visitEnd()
      cw.toByteArray
    }

    override def generateInner(): Array[Byte] = {
      val cw = new ClassWriter(0)

      cw.visit(
        Opcodes.V1_8,
        Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        innerName, null, "java/lang/Object", Array(authorizedIndicesIface)
      )
      cw.visitInnerClass(innerName, outerName, "AllIndicesAuthorized",
        Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
      cw.visitInnerClass(authorizedIndicesIface, "org/elasticsearch/xpack/core/security/authz/AuthorizationEngine", "AuthorizedIndices",
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)

      cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "metadata", projectMetadataDesc, null, null).visitEnd()

      // <init>(ProjectMetadata metadata)
      {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", s"($projectMetadataDesc)V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitFieldInsn(Opcodes.PUTFIELD, innerName, "metadata", projectMetadataDesc)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(2, 2)
        mv.visitEnd()
      }

      // public Set<String> all(IndexComponentSelector selector)
      {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "all", s"($indexComponentSelectorDesc)Ljava/util/Set;", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, innerName, "metadata", projectMetadataDesc)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, projectMetadataClass, "getIndicesLookup", "()Ljava/util/SortedMap;", false)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/SortedMap", "keySet", "()Ljava/util/Set;", true)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 2)
        mv.visitEnd()
      }

      // public boolean check(String name, IndexComponentSelector selector)
      {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "check", s"(Ljava/lang/String;$indexComponentSelectorDesc)Z", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 3)
        mv.visitEnd()
      }

      cw.visitEnd()
      cw.toByteArray
    }
  }
}

object CreateRorIndicesResolverClass {
  def apply(esVersion: SemVer): CreateRorIndicesResolverClass = new CreateRorIndicesResolverClass(esVersion)
}

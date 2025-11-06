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
package tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars.security

import better.files.File
import just.semver.SemVer
import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier
import tech.beshu.ror.tools.core.utils.EsUtil.{es8140, es900}

import java.io.InputStream

private[patches] class InterceptServiceAccountServiceInSecurity(esVersion: SemVer)
  extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/Security.class",
      processFileContent = doInterceptServiceAccountService
    )
  }

  private def doInterceptServiceAccountService(moduleInputStream: InputStream) = {
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
        case "createComponents" if (access & Opcodes.ACC_PUBLIC) != 0 =>
          esVersion match {
            case v if v >= es900 =>
              new InterceptServiceAccountServiceInCreateComponentsMethodForEsGreaterOrEqual900(
                super.visitMethod(access, name, descriptor, signature, exceptions)
              )
            case v if v >= es8140 =>
              new InterceptServiceAccountServiceInCreateComponentsMethodForEsGreaterOrEqual8140(
                super.visitMethod(access, name, descriptor, signature, exceptions)
              )
            case _ =>
              super.visitMethod(access, name, descriptor, signature, exceptions)
          }
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class InterceptServiceAccountServiceInCreateComponentsMethodForEsGreaterOrEqual900(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      val label1 = new Label()
      val label2 = new Label()
      underlying.visitTryCatchBlock(label0, label1, label2, "java/lang/Exception")
      underlying.visitLabel(label0)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label3 = new Label()
      underlying.visitLabel(label3)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "client", "()Lorg/elasticsearch/client/internal/Client;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label4 = new Label()
      underlying.visitLabel(label4)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "threadPool", "()Lorg/elasticsearch/threadpool/ThreadPool;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label5 = new Label()
      underlying.visitLabel(label5)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "clusterService", "()Lorg/elasticsearch/cluster/service/ClusterService;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label6 = new Label()
      underlying.visitLabel(label6)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "featureService", "()Lorg/elasticsearch/features/FeatureService;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label7 = new Label()
      underlying.visitLabel(label7)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "resourceWatcherService", "()Lorg/elasticsearch/watcher/ResourceWatcherService;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label8 = new Label()
      underlying.visitLabel(label8)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "scriptService", "()Lorg/elasticsearch/script/ScriptService;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label9 = new Label()
      underlying.visitLabel(label9)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "xContentRegistry", "()Lorg/elasticsearch/xcontent/NamedXContentRegistry;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label10 = new Label()
      underlying.visitLabel(label10)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "environment", "()Lorg/elasticsearch/env/Environment;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label11 = new Label()
      underlying.visitLabel(label11)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "indexNameExpressionResolver", "()Lorg/elasticsearch/cluster/metadata/IndexNameExpressionResolver;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label12 = new Label()
      underlying.visitLabel(label12)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "telemetryProvider", "()Lorg/elasticsearch/telemetry/TelemetryProvider;", true)
      underlying.visitTypeInsn(Opcodes.NEW, "org/elasticsearch/persistent/PersistentTasksService")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label13 = new Label()
      underlying.visitLabel(label13)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "clusterService", "()Lorg/elasticsearch/cluster/service/ClusterService;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "threadPool", "()Lorg/elasticsearch/threadpool/ThreadPool;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "client", "()Lorg/elasticsearch/client/internal/Client;", true)
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/elasticsearch/persistent/PersistentTasksService", "<init>", "(Lorg/elasticsearch/cluster/service/ClusterService;Lorg/elasticsearch/threadpool/ThreadPool;Lorg/elasticsearch/client/internal/Client;)V", false)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label14 = new Label()
      underlying.visitLabel(label14)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "linkedProjectConfigService", "()Lorg/elasticsearch/transport/LinkedProjectConfigService;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label15 = new Label()
      underlying.visitLabel(label15)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "projectResolver", "()Lorg/elasticsearch/cluster/project/ProjectResolver;", true)
      val label16 = new Label()
      underlying.visitLabel(label16)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/Security", "createComponents", "(Lorg/elasticsearch/client/internal/Client;Lorg/elasticsearch/threadpool/ThreadPool;Lorg/elasticsearch/cluster/service/ClusterService;Lorg/elasticsearch/features/FeatureService;Lorg/elasticsearch/watcher/ResourceWatcherService;Lorg/elasticsearch/script/ScriptService;Lorg/elasticsearch/xcontent/NamedXContentRegistry;Lorg/elasticsearch/env/Environment;Lorg/elasticsearch/cluster/metadata/IndexNameExpressionResolver;Lorg/elasticsearch/telemetry/TelemetryProvider;Lorg/elasticsearch/persistent/PersistentTasksService;Lorg/elasticsearch/transport/LinkedProjectConfigService;Lorg/elasticsearch/cluster/project/ProjectResolver;)Ljava/util/Collection;", false)
      underlying.visitVarInsn(Opcodes.ASTORE, 2)
      val label17 = new Label()
      underlying.visitLabel(label17)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true)
      underlying.visitVarInsn(Opcodes.ASTORE, 3)
      val label18 = new Label()
      underlying.visitLabel(label18)
      underlying.visitFrame(Opcodes.F_APPEND, 2, Array("java/util/Collection", "java/util/Iterator"), 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 3)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true)
      val label19 = new Label()
      underlying.visitJumpInsn(Opcodes.IFEQ, label19)
      underlying.visitVarInsn(Opcodes.ALOAD, 3)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true)
      underlying.visitVarInsn(Opcodes.ASTORE, 4)
      val label20 = new Label()
      underlying.visitLabel(label20)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitTypeInsn(Opcodes.INSTANCEOF, "org/elasticsearch/xpack/security/authc/service/ServiceAccountService")
      val label21 = new Label()
      underlying.visitJumpInsn(Opcodes.IFEQ, label21)
      val label22 = new Label()
      underlying.visitLabel(label22)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/security/ServiceAccountServiceBridge", "publish", "(Ljava/lang/Object;)V", false)
      val label23 = new Label()
      underlying.visitLabel(label23)
      underlying.visitJumpInsn(Opcodes.GOTO, label19)
      underlying.visitLabel(label21)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitJumpInsn(Opcodes.GOTO, label18)
      underlying.visitLabel(label19)
      underlying.visitFrame(Opcodes.F_CHOP, 1, null, 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitLabel(label1)
      underlying.visitInsn(Opcodes.ARETURN)
      underlying.visitLabel(label2)
      underlying.visitFrame(Opcodes.F_FULL, 2, Array("org/elasticsearch/xpack/security/Security", "org/elasticsearch/plugins/Plugin$PluginServices"), 1, Array("java/lang/Exception"))
      underlying.visitVarInsn(Opcodes.ASTORE, 2)
      val label24 = new Label()
      underlying.visitLabel(label24)
      underlying.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitLdcInsn("security initialization failed")
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;Ljava/lang/Throwable;)V", false)
      underlying.visitInsn(Opcodes.ATHROW)
      val label25 = new Label()
      underlying.visitLabel(label25)
      underlying.visitLocalVariable("c", "Ljava/lang/Object;", null, label20, label21, 4)
      underlying.visitLocalVariable("components", "Ljava/util/Collection;", "Ljava/util/Collection<Ljava/lang/Object;>;", label17, label2, 2)
      underlying.visitLocalVariable("e", "Ljava/lang/Exception;", null, label24, label25, 2)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/Security;", null, label0, label25, 0)
      underlying.visitLocalVariable("services", "Lorg/elasticsearch/plugins/Plugin$PluginServices;", null, label0, label25, 1)
      underlying.visitMaxs(16, 5)
      underlying.visitEnd()
    }
  }

  private class InterceptServiceAccountServiceInCreateComponentsMethodForEsGreaterOrEqual8140(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      val label0 = new Label()
      val label1 = new Label()
      val label2 = new Label()
      underlying.visitTryCatchBlock(label0, label1, label2, "java/lang/Exception")
      underlying.visitLabel(label0)
      underlying.visitVarInsn(Opcodes.ALOAD, 0)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label3 = new Label()
      underlying.visitLabel(label3)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "client", "()Lorg/elasticsearch/client/internal/Client;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label4 = new Label()
      underlying.visitLabel(label4)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "threadPool", "()Lorg/elasticsearch/threadpool/ThreadPool;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label5 = new Label()
      underlying.visitLabel(label5)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "clusterService", "()Lorg/elasticsearch/cluster/service/ClusterService;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label6 = new Label()
      underlying.visitLabel(label6)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "featureService", "()Lorg/elasticsearch/features/FeatureService;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label7 = new Label()
      underlying.visitLabel(label7)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "resourceWatcherService", "()Lorg/elasticsearch/watcher/ResourceWatcherService;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label8 = new Label()
      underlying.visitLabel(label8)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "scriptService", "()Lorg/elasticsearch/script/ScriptService;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label9 = new Label()
      underlying.visitLabel(label9)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "xContentRegistry", "()Lorg/elasticsearch/xcontent/NamedXContentRegistry;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label10 = new Label()
      underlying.visitLabel(label10)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "environment", "()Lorg/elasticsearch/env/Environment;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label11 = new Label()
      underlying.visitLabel(label11)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "nodeEnvironment", "()Lorg/elasticsearch/env/NodeEnvironment;", true)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/env/NodeEnvironment", "nodeMetadata", "()Lorg/elasticsearch/env/NodeMetadata;", false)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label12 = new Label()
      underlying.visitLabel(label12)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "indexNameExpressionResolver", "()Lorg/elasticsearch/cluster/metadata/IndexNameExpressionResolver;", true)
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      val label13 = new Label()
      underlying.visitLabel(label13)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/elasticsearch/plugins/Plugin$PluginServices", "telemetryProvider", "()Lorg/elasticsearch/telemetry/TelemetryProvider;", true)
      val label14 = new Label()
      underlying.visitLabel(label14)
      underlying.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/elasticsearch/xpack/security/Security", "createComponents", "(Lorg/elasticsearch/client/internal/Client;Lorg/elasticsearch/threadpool/ThreadPool;Lorg/elasticsearch/cluster/service/ClusterService;Lorg/elasticsearch/features/FeatureService;Lorg/elasticsearch/watcher/ResourceWatcherService;Lorg/elasticsearch/script/ScriptService;Lorg/elasticsearch/xcontent/NamedXContentRegistry;Lorg/elasticsearch/env/Environment;Lorg/elasticsearch/env/NodeMetadata;Lorg/elasticsearch/cluster/metadata/IndexNameExpressionResolver;Lorg/elasticsearch/telemetry/TelemetryProvider;)Ljava/util/Collection;", false)
      underlying.visitVarInsn(Opcodes.ASTORE, 2)
      val label15 = new Label()
      underlying.visitLabel(label15)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true)
      underlying.visitVarInsn(Opcodes.ASTORE, 3)
      val label16 = new Label()
      underlying.visitLabel(label16)
      underlying.visitFrame(Opcodes.F_APPEND, 2, Array("java/util/Collection", "java/util/Iterator"), 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 3)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true)
      val label17 = new Label()
      underlying.visitJumpInsn(Opcodes.IFEQ, label17)
      underlying.visitVarInsn(Opcodes.ALOAD, 3)
      underlying.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true)
      underlying.visitVarInsn(Opcodes.ASTORE, 4)
      val label18 = new Label()
      underlying.visitLabel(label18)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitTypeInsn(Opcodes.INSTANCEOF, "org/elasticsearch/xpack/security/authc/service/ServiceAccountService")
      val label19 = new Label()
      underlying.visitJumpInsn(Opcodes.IFEQ, label19)
      val label20 = new Label()
      underlying.visitLabel(label20)
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitMethodInsn(Opcodes.INVOKESTATIC, "org/elasticsearch/xpack/security/ServiceAccountServiceBridge", "publish", "(Ljava/lang/Object;)V", false)
      val label21 = new Label()
      underlying.visitLabel(label21)
      underlying.visitJumpInsn(Opcodes.GOTO, label17)
      underlying.visitLabel(label19)
      underlying.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
      underlying.visitJumpInsn(Opcodes.GOTO, label16)
      underlying.visitLabel(label17)
      underlying.visitFrame(Opcodes.F_CHOP, 1, null, 0, null)
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitLabel(label1)
      underlying.visitInsn(Opcodes.ARETURN)
      underlying.visitLabel(label2)
      underlying.visitFrame(Opcodes.F_FULL, 2, Array("org/elasticsearch/xpack/security/Security", "org/elasticsearch/plugins/Plugin$PluginServices"), 1, Array("java/lang/Exception"))
      underlying.visitVarInsn(Opcodes.ASTORE, 2)
      val label22 = new Label()
      underlying.visitLabel(label22)
      underlying.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
      underlying.visitInsn(Opcodes.DUP)
      underlying.visitLdcInsn("security initialization failed")
      underlying.visitVarInsn(Opcodes.ALOAD, 2)
      underlying.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;Ljava/lang/Throwable;)V", false)
      underlying.visitInsn(Opcodes.ATHROW)
      val label23 = new Label()
      underlying.visitLabel(label23)
      underlying.visitLocalVariable("c", "Ljava/lang/Object;", null, label18, label19, 4)
      underlying.visitLocalVariable("components", "Ljava/util/Collection;", "Ljava/util/Collection<Ljava/lang/Object;>;", label15, label2, 2)
      underlying.visitLocalVariable("e", "Ljava/lang/Exception;", null, label22, label23, 2)
      underlying.visitLocalVariable("this", "Lorg/elasticsearch/xpack/security/Security;", null, label0, label23, 0)
      underlying.visitLocalVariable("services", "Lorg/elasticsearch/plugins/Plugin$PluginServices;", null, label0, label23, 1)
      underlying.visitMaxs(12, 5)
      underlying.visitEnd()
    }
  }
}

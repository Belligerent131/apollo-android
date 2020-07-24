package com.apollographql.apollo.gradle.internal

import com.apollographql.apollo.api.internal.QueryDocumentMinifier
import com.apollographql.apollo.compiler.DefaultPackageNameProvider
import com.apollographql.apollo.compiler.ir.CodeGenerationIR
import com.apollographql.apollo.compiler.operationoutput.OperationDescriptor
import com.apollographql.apollo.compiler.operationoutput.toJson
import com.apollographql.apollo.compiler.parser.graphql.GraphQLDocumentParser
import com.apollographql.apollo.compiler.parser.introspection.IntrospectionSchema
import com.apollographql.apollo.compiler.parser.sdl.GraphSdlSchema
import com.apollographql.apollo.compiler.parser.sdl.toIntrospectionSchema
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
abstract class ApolloGenerateIRTask : DefaultTask() {
  @get:InputFiles
  @get:SkipWhenEmpty
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val graphqlFiles: ConfigurableFileCollection

  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val schemaFile: RegularFileProperty

  @get:OutputFile
  abstract val irFile: RegularFileProperty

  @get:OutputFile
  abstract val operationDescriptorListFile: RegularFileProperty

  @get:Input
  abstract val rootFolders: ListProperty<String>

  @get:Input
  @get:Optional
  abstract val rootPackageName: Property<String>

  @TaskAction
  fun taskAction() {
    val codeGenerationIR = if (schemaFile.isPresent) {
      val realSchemaFile = schemaFile.get().asFile

      val introspectionSchema = if (realSchemaFile.extension == "json") {
        IntrospectionSchema.invoke(realSchemaFile)
      } else {
        GraphSdlSchema(realSchemaFile).toIntrospectionSchema()
      }

      val packageNameProvider = DefaultPackageNameProvider(
          rootFolders = rootFolders.get().map { project.file(it) },
          rootPackageName = rootPackageName.getOrElse(""),
          schemaFile = realSchemaFile
      )

      val files = graphqlFiles.files
      sanityChecks(packageNameProvider, files)

      GraphQLDocumentParser(introspectionSchema, packageNameProvider).parse(files)
    } else {
      // No schema found, this happens if no schema.json is set, for an exemple for tests
      CodeGenerationIR(
          operations = emptyList(),
          fragments = emptyList(),
          typesUsed = emptyList(),
          typesPackageName = "",
          fragmentsPackageName = ""
      )
    }

    irFile.asFile.get().parentFile.mkdirs()
    irFile.asFile.get().writeText(codeGenerationIR.toJson())

    operationDescriptorListFile.get().asFile.writeText(
        codeGenerationIR.operations.map {
          OperationDescriptor(
              name = it.operationName,
              packageName = it.packageName,
              filePath = it.filePath,
              source = QueryDocumentMinifier.minify(it.sourceWithFragments)
          )
        }.toJson("    "))
  }

  companion object {
    private fun sanityChecks(packageNameProvider: DefaultPackageNameProvider, files: Set<File>) {
      val map = files.groupBy { packageNameProvider.filePackageName(it.normalize().absolutePath) to it.nameWithoutExtension }

      map.values.forEach {
        require(it.size == 1) {
          "ApolloGraphQL: duplicate(s) graphql file(s) found:\n" +
              it.map { it.absolutePath }.joinToString("\n")
        }
      }
    }
  }
}
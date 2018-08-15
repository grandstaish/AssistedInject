/*
 * Copyright (C) 2017 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.inject.assisted.processor

import com.google.auto.common.MoreTypes
import com.google.auto.service.AutoService
import com.squareup.inject.assisted.AssistedInject
import com.squareup.inject.assisted.processor.internal.cast
import com.squareup.inject.assisted.processor.internal.duplicates
import com.squareup.inject.assisted.processor.internal.findElementsAnnotatedWith
import com.squareup.inject.assisted.processor.internal.hasAnnotation
import com.squareup.javapoet.JavaFile
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind.CLASS
import javax.lang.model.element.ElementKind.CONSTRUCTOR
import javax.lang.model.element.ElementKind.INTERFACE
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier.PRIVATE
import javax.lang.model.element.Modifier.STATIC
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement
import javax.lang.model.util.Types
import javax.tools.Diagnostic.Kind.ERROR

@AutoService(Processor::class)
class AssistedInjectProcessor : AbstractProcessor() {
  override fun getSupportedSourceVersion() = SourceVersion.latest()
  override fun getSupportedAnnotationTypes() = setOf(
      AssistedInject::class.java.canonicalName,
      AssistedInject.Factory::class.java.canonicalName)

  override fun init(env: ProcessingEnvironment) {
    super.init(env)
    this.types = env.typeUtils
    this.messager = env.messager
    this.filer = env.filer
  }

  private lateinit var types: Types
  private lateinit var messager: Messager
  private lateinit var filer: Filer

  override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
    val factoryTypes = roundEnv.findElementsAnnotatedWith<AssistedInject.Factory>()
    factoryTypes
        .filterNot { it.enclosingElement.kind == CLASS }
        .forEach {
          messager.printMessage(ERROR, "@AssistedInject.Factory must be declared as a nested type.", it)
        }

    // Grab types with only an @AssistedInject.Factory so we can detect missing constructor annotations.
    val typeWrappersWithFactories = factoryTypes
        .map { it.enclosingElement }
        .filter { it.kind == CLASS }
        .map { MoreTypes.equivalence().wrap(it.asType()) }

    // Grab types with only @AssistedInject so we can detect missing @AssistedInject.Factory types.
    val typeWrappersWithAssisted = roundEnv.findElementsAnnotatedWith<AssistedInject>()
        .map { it.enclosingElement as TypeElement }
        .map { MoreTypes.equivalence().wrap(it.asType()) }

    (typeWrappersWithFactories + typeWrappersWithAssisted)
        .toSet()
        .map { types.asElement(it.get()) as TypeElement }
        .forEach {
          try {
            val request = parseRequest(it)
            JavaFile.builder(request.generatedClassName.packageName(), request.brewJava())
                .addFileComment("Generated by @AssistedInject. Do not modify!")
                .build()
                .writeTo(filer)
          } catch (e: StopProcessingException) {
            messager.printMessage(ERROR, e.message, e.originatingElement)
          } catch (e: Exception) {
            messager.printMessage(ERROR, "Uncaught error: ${e.message}")
          }
        }

    return false
  }

  private fun parseRequest(type: TypeElement): AssistedInjectRequest {
    if (PRIVATE in type.modifiers) {
      throw StopProcessingException("@AssistedInject-using types must not be private", type)
    }
    if (type.enclosingElement.kind == CLASS && STATIC !in type.modifiers) {
      throw StopProcessingException("Nested @AssistedInject-using types must be static", type)
    }

    val constructor = findAssistedConstructor(type)

    val parameterKeys = constructor.parameters.map(VariableElement::asParameterKey)
    val (assistedKeys, providedKeys) = parameterKeys.partition(ParameterKey::isAssisted)
    validateAssistedKeys(constructor, assistedKeys)
    validateProvidedKeys(constructor, providedKeys)

    val factoryType = findFactoryType(type)
    val factoryMethod = findFactoryMethod(factoryType, type)
    validateFactoryKeys(factoryMethod, assistedKeys.map(ParameterKey::key).toSet())

    return AssistedInjectRequest(type, factoryType, factoryMethod, parameterKeys)
  }

  private fun findAssistedConstructor(type: TypeElement): ExecutableElement {
    val constructors = type.enclosedElements
        .filter { it.kind == CONSTRUCTOR }
        .cast<ExecutableElement>()

    val assistedConstructors = constructors.filter { it.hasAnnotation<AssistedInject>() }
    if (assistedConstructors.isEmpty()) {
      throw StopProcessingException(
          "Assisted injection requires an @AssistedInject-annotated constructor " +
              "with at least one @Assisted parameter.", type)
    }
    if (assistedConstructors.size > 1) {
      throw StopProcessingException("Multiple @AssistedInject-annotated constructors found.", type)
    }
    val constructor = assistedConstructors.single()

    if (PRIVATE in constructor.modifiers) {
      throw StopProcessingException("@AssistedInject constructor must not be private.", constructor)
    }
    return constructor
  }

  private fun validateProvidedKeys(method: ExecutableElement, providedKeys: List<ParameterKey>) {
    if (providedKeys.isEmpty()) {
      throw StopProcessingException(
          "Assisted injection requires at least one non-@Assisted parameter.", method)
    }
    val duplicateKeys = providedKeys.duplicates()
    if (duplicateKeys.isNotEmpty()) {
      throw StopProcessingException(
          "Duplicate non-@Assisted parameters declared. Forget a qualifier annotation?"
              + duplicateKeys.toSet().joinToString("\n * ", prefix = "\n * "),
          method)
    }
  }

  private fun validateAssistedKeys(method: ExecutableElement, assistedKeys: List<ParameterKey>) {
    if (assistedKeys.isEmpty()) {
      throw StopProcessingException(
          "Assisted injection requires at least one @Assisted parameter.", method)
    }
    val duplicateKeys = assistedKeys.duplicates()
    if (duplicateKeys.isNotEmpty()) {
      throw StopProcessingException(
          "Duplicate @Assisted parameters declared. Forget a qualifier annotation?"
              + duplicateKeys.toSet().joinToString("\n * ", prefix = "\n * "),
          method)
    }
  }

  private fun findFactoryType(type: TypeElement): TypeElement {
    val types = type.enclosedElements
        .filterIsInstance<TypeElement>()
        .filter { it.hasAnnotation<AssistedInject.Factory>() }
    if (types.isEmpty()) {
      throw StopProcessingException("No nested @AssistedInject.Factory found.", type)
    }
    if (types.size > 1) {
      throw StopProcessingException("Multiple @AssistedInject.Factory types found.", type)
    }
    val factory = types.single()
    if (factory.kind != INTERFACE) {
      throw StopProcessingException("@AssistedInject.Factory must be an interface.", factory)
    }
    if (PRIVATE in factory.modifiers) {
      throw StopProcessingException("@AssistedInject.Factory must not be private.", factory)
    }
    return factory
  }

  private fun findFactoryMethod(factory: TypeElement, type: TypeElement): ExecutableElement {
    val methods = factory.enclosedElements
        .filterIsInstance<ExecutableElement>() // Ignore non-method elements like constants.
        .filterNot { it.isDefault } // Ignore default methods for convenience overloads.
        .filterNot { STATIC in it.modifiers } // Ignore static helper methods.
        .filterNot { PRIVATE in it.modifiers } // Ignore private helper methods for default methods.
    if (methods.isEmpty()) {
      throw StopProcessingException("Factory interface does not define a factory method.", factory)
    }
    if (methods.size > 1) {
      throw StopProcessingException("Factory interface defines multiple factory methods.", factory)
    }
    val method = methods.single()
    //if (!types.isAssignable(type.asType(), method.returnType)) {
    //  throw StopProcessingException("Factory method returns incorrect type. "
    //      + "Must be ${type.simpleName} or one of its supertypes.", method)
    //}
    return method
  }

  private fun validateFactoryKeys(method: ExecutableElement, expectedKeys: Set<Key>) {
    val keys = method.parameters.map(VariableElement::asKey).toSet()
    if (keys != expectedKeys) {
      var message = "Factory method parameters do not match constructor @Assisted parameters."

      val missingKeys = expectedKeys - keys
      if (missingKeys.isNotEmpty()) {
        message += missingKeys.joinToString("\n * ", prefix = "\n\nMissing:\n * ")
      }

      val unknownKeys = keys - expectedKeys
      if (unknownKeys.isNotEmpty()) {
        message += unknownKeys.joinToString("\n * ", prefix = "\n\nUnknown:\n * ")
      }

      throw StopProcessingException(message, method)
    }
  }
}

internal class StopProcessingException(
    message: String,
    val originatingElement: Element? = null
) : Exception(message)

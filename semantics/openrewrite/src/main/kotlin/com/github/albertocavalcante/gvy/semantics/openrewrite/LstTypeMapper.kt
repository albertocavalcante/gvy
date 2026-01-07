package com.github.albertocavalcante.gvy.semantics.openrewrite

import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import org.openrewrite.java.tree.JavaType

/**
 * Maps OpenRewrite LST types (JavaType) to SemanticType.
 *
 * This is the bridge between OpenRewrite's type system and the semantics layer.
 * OpenRewrite uses JavaType hierarchy for both Java and Groovy types.
 */
object LstTypeMapper {

    /**
     * Convert an OpenRewrite JavaType to a SemanticType.
     *
     * @param javaType The OpenRewrite type to convert, can be null
     * @return The corresponding SemanticType, or null if input is null
     */
    fun toSemanticType(javaType: JavaType?): SemanticType? = when (javaType) {
        null -> null
        is JavaType.Primitive -> mapPrimitive(javaType)
        is JavaType.Array -> mapArray(javaType)
        is JavaType.Parameterized -> mapParameterized(javaType)
        is JavaType.Class -> mapClass(javaType)
        is JavaType.ShallowClass -> mapShallowClass(javaType)
        is JavaType.Unknown -> SemanticType.Unknown("Unknown type from OpenRewrite LST")
        is JavaType.GenericTypeVariable -> mapGenericTypeVariable(javaType)
        is JavaType.Method -> mapMethodType(javaType)
        is JavaType.Variable -> mapVariableType(javaType)
        else -> SemanticType.Unknown("Unsupported JavaType: ${javaType::class.simpleName}")
    }

    private fun mapPrimitive(primitive: JavaType.Primitive): SemanticType = when (primitive) {
        JavaType.Primitive.Boolean -> SemanticType.Primitive(PrimitiveKind.BOOLEAN)
        JavaType.Primitive.Byte -> SemanticType.Primitive(PrimitiveKind.BYTE)
        JavaType.Primitive.Char -> SemanticType.Primitive(PrimitiveKind.CHAR)
        JavaType.Primitive.Double -> SemanticType.Primitive(PrimitiveKind.DOUBLE)
        JavaType.Primitive.Float -> SemanticType.Primitive(PrimitiveKind.FLOAT)
        JavaType.Primitive.Int -> SemanticType.Primitive(PrimitiveKind.INT)
        JavaType.Primitive.Long -> SemanticType.Primitive(PrimitiveKind.LONG)
        JavaType.Primitive.Short -> SemanticType.Primitive(PrimitiveKind.SHORT)
        JavaType.Primitive.Void -> SemanticType.Primitive(PrimitiveKind.VOID)
        JavaType.Primitive.String -> SemanticType.Known("java.lang.String")
        JavaType.Primitive.None -> SemanticType.Dynamic()
        JavaType.Primitive.Null -> SemanticType.Null
    }

    private fun mapArray(array: JavaType.Array): SemanticType {
        val componentType = toSemanticType(array.elemType)
            ?: SemanticType.Unknown("Unknown array component type")
        return SemanticType.Array(componentType)
    }

    private fun mapParameterized(parameterized: JavaType.Parameterized): SemanticType {
        val rawType = parameterized.type
        val fqn = rawType.fullyQualifiedName
        val typeArgs = parameterized.typeParameters.mapNotNull { toSemanticType(it) }
        return SemanticType.Known(fqn, typeArgs)
    }

    private fun mapClass(classType: JavaType.Class): SemanticType = SemanticType.Known(classType.fullyQualifiedName)

    private fun mapShallowClass(shallowClass: JavaType.ShallowClass): SemanticType =
        SemanticType.Known(shallowClass.fullyQualifiedName)

    private fun mapGenericTypeVariable(typeVar: JavaType.GenericTypeVariable): SemanticType {
        // For generic type variables, try to use the bound if available
        val bounds = typeVar.bounds
        return if (bounds.isNotEmpty()) {
            toSemanticType(bounds.first()) ?: SemanticType.Dynamic("Unbound type variable: ${typeVar.name}")
        } else {
            SemanticType.Dynamic("Type variable: ${typeVar.name}")
        }
    }

    private fun mapMethodType(method: JavaType.Method): SemanticType {
        // For method types, return the return type
        return toSemanticType(method.returnType)
            ?: SemanticType.Unknown("Method with unknown return type")
    }

    private fun mapVariableType(variable: JavaType.Variable): SemanticType = toSemanticType(variable.type)
        ?: SemanticType.Unknown("Variable with unknown type: ${variable.name}")
}

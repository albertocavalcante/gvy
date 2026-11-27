// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

/** What kind of input a facts file describes. */
public enum class InputKind(internal val code: Int) {
    /** The runtime image of a JDK. */
    JDK_IMAGE(0),

    /** A jar file. */
    JAR(1),
}

/**
 * The uncompressed identity of a facts file.
 *
 * [inputSha256] and [inputLength] identify the input bytes; they are the key of a file. [coordinate] is only
 * informational (for example `group:artifact:version`) and is never a key. [producerVersion] and [coordinate] are
 * limited to [FactsFormat.MAX_HEADER_STRING_BYTES] UTF-8 bytes each; the writer rejects a longer value.
 */
public data class FactsHeader(
    val producerVersion: String,
    val inputKind: InputKind,
    val coordinate: String?,
    val inputSha256: Sha256,
    val inputLength: Long,
) {
    init {
        require(inputLength >= 0) { "The input length must not be negative: $inputLength" }
    }
}

/** A package of the input; [module] is set only for a JDK image. */
public data class PackageFact(val name: String, val module: String?)

/**
 * A class of the input.
 *
 * [internalName] uses slashes (`java/util/Map$Entry`) and [packageName] uses dots (`java.util`, empty for the
 * unnamed package). [simpleName] is empty for an anonymous class. [outerClass] is the internal name of the enclosing
 * class: for a member class its declaring class, and for a local or anonymous class the class its `EnclosingMethod`
 * names. [release] is 0 for the base copy and N for a copy under `META-INF/versions/N`. The model does not check that
 * these fields agree with each other. [accessFlags] must be within 0..[FactsFormat.MAX_ACCESS_FLAGS] and [release]
 * within 0..[FactsFormat.MAX_RELEASE]. For a class with an `InnerClasses` entry for itself the flags are the ones of
 * that entry, which is what `Class.getModifiers` reports, so `static`, `private` and `protected` are kept; a class
 * without such an entry is top-level and has the flags of its class file.
 */
public data class ClassFact(
    val internalName: String,
    val packageName: String,
    val simpleName: String,
    val outerClass: String?,
    val accessFlags: Int,
    val release: Int = 0,
) {
    init {
        require(accessFlags in 0..FactsFormat.MAX_ACCESS_FLAGS) { "Access flags out of range: $accessFlags" }
        require(release in 0..FactsFormat.MAX_RELEASE) { "Release out of range: $release" }
    }
}

/**
 * The package and class names of the input. The writer sorts both lists; [ClassFact.packageName] of every class
 * must name a package in [packages], package names must be unique, and (internal name, release) must be unique
 * among [classes].
 */
public data class NamesSection(val packages: List<PackageFact>, val classes: List<ClassFact>)

/** Whether a member is a field or a method (constructors and initializers are methods). */
public enum class MemberKind(internal val code: Int) {
    FIELD(0),
    METHOD(1),
}

/**
 * A field or method.
 *
 * [descriptor] is the JVM descriptor. [parameterNames] is null when the class file has no usable `MethodParameters`
 * attribute (missing, or with a count that differs from the descriptor's), which differs from an empty list. An empty
 * name means the attribute names no parameter at that position. The builder stores an empty list for every method
 * with no parameters, with or without the attribute. [genericSignature] is null when absent or not
 * recorded.
 * [accessFlags] follows the rules of [ClassFact.accessFlags]; [FactsFormat.DEPRECATED_FLAG] marks deprecation.
 */
public data class MemberFact(
    val kind: MemberKind,
    val accessFlags: Int,
    val name: String,
    val descriptor: String,
    val parameterNames: List<String>? = null,
    val genericSignature: String? = null,
) {
    init {
        require(accessFlags in 0..FactsFormat.MAX_ACCESS_FLAGS) { "Access flags out of range: $accessFlags" }
    }
}

/**
 * The supertypes and members of one class, identified by [internalName] and [release], which must name a
 * [ClassFact] of the [NamesSection]: the file stores the class as its position in the names section.
 *
 * The writer keeps the order of [interfaces] and [MemberFact.parameterNames] because it is meaningful, and sorts
 * [members] by name and then descriptor, which must be unique within the class. [release] follows the rules of
 * [ClassFact.release].
 */
public data class ClassTypes(
    val internalName: String,
    val release: Int,
    val superName: String?,
    val interfaces: List<String>,
    val members: List<MemberFact>,
) {
    init {
        require(release in 0..FactsFormat.MAX_RELEASE) { "Release out of range: $release" }
    }
}

/**
 * The supertypes and members of the classes of the input. The writer sorts [classes]; (internal name, release)
 * must be unique and name a [ClassFact]. A [ClassFact] without a [ClassTypes] is allowed.
 */
public data class TypesSection(val classes: List<ClassTypes>)

/** Whether an extension method is called on an instance or on the class itself. */
public enum class ExtensionKind(internal val code: Int) {
    INSTANCE(0),
    STATIC(1),
}

/** Where an extension holder was declared. */
public enum class ExtensionSource(internal val code: Int) {
    /** A `META-INF/groovy` extension-module descriptor. */
    META_INF_GROOVY(0),

    /** A `META-INF/services` extension-module descriptor. */
    META_INF_SERVICES(1),

    /** Groovy's own fixed list of core holder classes. */
    CORE_LIST(2),
}

/**
 * A class declared to hold extension methods, together with the module that declared it.
 * The methods themselves are the members of [holderClass] in [TypesSection].
 */
public data class ExtensionFact(
    val holderClass: String,
    val kind: ExtensionKind,
    val source: ExtensionSource,
    val moduleName: String,
    val moduleVersion: String,
)

/** The declared extension holders of the input. The writer sorts [entries] by all of their fields. */
public data class ExtensionsSection(val entries: List<ExtensionFact>)

/**
 * Everything a facts file holds. The classes of the model are immutable values; the lists they hold are kept as
 * given, so a caller must not modify a list after passing it.
 */
public data class Facts(
    val header: FactsHeader,
    val names: NamesSection,
    val types: TypesSection,
    val extensions: ExtensionsSection,
)

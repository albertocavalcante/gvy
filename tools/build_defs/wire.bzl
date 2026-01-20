"""Wire protobuf rule for generating Kotlin code from .proto files.

This rule wraps Square's Wire compiler to generate idiomatic Kotlin data classes
from Protocol Buffer definitions.

Usage:
    load("//tools/build_defs:wire.bzl", "wire_proto_library")

    wire_proto_library(
        name = "scip_proto",
        srcs = ["scip.proto"],
        visibility = ["//visibility:public"],
        deps = ["@maven//:com_squareup_wire_wire_runtime"],
    )

    # Then depend on it in kt_library:
    kt_library(
        name = "scip",
        deps = [":scip_proto"],
    )
"""

load("//tools/build_defs:kotlin.bzl", "kt_library")

def wire_proto_library(name, srcs, visibility = None, deps = None):
    """Generates Kotlin code from proto files and compiles into a library.

    Args:
        name: Target name for the compiled library
        srcs: Proto files to compile
        visibility: Visibility of the target
        deps: Additional dependencies (wire-runtime is added automatically)
    """
    gen_name = name + "_gen"

    # Generate Kotlin sources from proto files
    _wire_codegen(
        name = gen_name,
        srcs = srcs,
    )

    # Compile the generated Kotlin code into a library
    # Use -jvm variants for Kotlin Multiplatform artifacts
    all_deps = [
        "@maven//:com_squareup_okio_okio_jvm",
        "@maven//:com_squareup_wire_wire_runtime_jvm",
        "@maven//:org_jetbrains_kotlin_kotlin_stdlib",
    ]
    if deps:
        all_deps = all_deps + deps

    kt_library(
        name = name,
        srcs = [":" + gen_name],
        visibility = visibility,
        deps = all_deps,
    )

def _wire_codegen_impl(ctx):
    """Implementation of wire codegen rule.

    Generates Kotlin source files and packages them into a srcjar
    that can be consumed by kt_library.
    """

    # Temporary directory for generated Kotlin files
    output_dir = ctx.actions.declare_directory(ctx.label.name + "_wire")

    # Final output: srcjar that kt_library can consume
    srcjar = ctx.actions.declare_file(ctx.label.name + ".srcjar")

    # Build the Wire compiler command
    # Wire compiler expects: --proto_path=<dir> --kotlin_out=<dir> <proto_files>
    proto_paths = {}
    proto_files = []
    for src in ctx.files.srcs:
        proto_paths[src.dirname] = True
        proto_files.append(src.basename)

    # Build command arguments
    args = ctx.actions.args()
    for proto_path in proto_paths.keys():
        args.add("--proto_path=" + proto_path)
    args.add("--kotlin_out=" + output_dir.path)
    for proto_file in proto_files:
        args.add(proto_file)

    # Get the Wire compiler executable (java_binary)
    wire_compiler = ctx.executable._wire_compiler

    # Run the Wire compiler
    ctx.actions.run(
        inputs = ctx.files.srcs,
        outputs = [output_dir],
        executable = wire_compiler,
        arguments = [args],
        tools = [wire_compiler],
        mnemonic = "WireCompile",
        progress_message = "Generating Kotlin from proto: %s" % ", ".join([f.basename for f in ctx.files.srcs]),
    )

    # Package generated files into a srcjar that kt_library can consume
    # A srcjar is just a zip file with .kt sources - use zip command
    # Need to use absolute paths since we cd into the output directory
    ctx.actions.run_shell(
        inputs = [output_dir],
        outputs = [srcjar],
        command = 'SRCJAR="$(pwd)/$2" && cd "$1" && zip -rq "$SRCJAR" .',
        arguments = [output_dir.path, srcjar.path],
        mnemonic = "WireSrcjar",
        progress_message = "Creating srcjar for: %s" % ctx.label.name,
    )

    return [
        DefaultInfo(files = depset([srcjar])),
    ]

_wire_codegen = rule(
    implementation = _wire_codegen_impl,
    attrs = {
        "srcs": attr.label_list(
            allow_files = [".proto"],
            mandatory = True,
            doc = "Proto files to compile with Wire",
        ),
        "_wire_compiler": attr.label(
            default = "//tools:wire_compiler",
            executable = True,
            cfg = "exec",
            doc = "Wire compiler executable (java_binary)",
        ),
    },
    doc = "Internal rule: generates Kotlin code from proto files using Wire compiler",
)

# Custom Kotlin toolchain for Buck2
#
# This allows using a user-provided Kotlin version instead of
# the prelude's default, similar to rules_kotlin's define_kt_toolchain().

load("@prelude//kotlin:kotlin_toolchain.bzl", "KotlinToolchainInfo")
load("@prelude//java:java_toolchain.bzl", "DepFiles")

def _kotlin_toolchain_impl(ctx):
    """Implementation of the custom Kotlin toolchain rule."""
    return [
        DefaultInfo(),
        KotlinToolchainInfo(
            annotation_processing_jar = ctx.attrs.annotation_processing_jar,
            class_loader_bootstrapper = None,
            compile_kotlin = ctx.attrs.compile_kotlin,
            dep_files = DepFiles("none"),
            kapt_base64_encoder = ctx.attrs.kapt_base64_encoder,
            kotlinc = ctx.attrs.kotlinc,
            kotlincd = None,
            kotlin_stdlib = ctx.attrs.kotlin_stdlib,
            kotlin_version = ctx.attrs.kotlin_version,
            kotlin_home_libraries = [],
            enable_incremental_compilation = False,
            ksp2_enable_incremental_processing = False,
            kotlinc_protocol = "classic",
            kosabi_stubs_gen_k2_plugin = None,
            kosabi_stubs_gen_plugin = None,
            kosabi_source_modifier_plugin = None,
            kosabi_applicability_plugin = None,
            kosabi_jvm_abi_gen_plugin = None,
            jvm_abi_gen_plugin = None,
            kotlincd_debug_port = None,
            kotlincd_debug_target = None,
            kotlincd_jvm_args = [],
            kotlincd_jvm_args_target = [],
            kotlincd_main_class = None,
            kotlincd_worker = None,
            track_class_usage_plugin = None,
            kotlin_error_handler = None,
            kosabi_jvm_abi_gen_k2_plugin = None,
            semanticdb_kotlinc = None,
            semanticdb_sourceroot = None,
        ),
    ]

kotlin_toolchain = rule(
    impl = _kotlin_toolchain_impl,
    is_toolchain_rule = True,
    attrs = {
        "annotation_processing_jar": attrs.dep(),
        "compile_kotlin": attrs.dep(providers = [RunInfo]),
        "kapt_base64_encoder": attrs.dep(providers = [RunInfo]),
        "kotlin_stdlib": attrs.dep(),
        "kotlin_version": attrs.string(),
        "kotlinc": attrs.dep(providers = [RunInfo]),
    },
)

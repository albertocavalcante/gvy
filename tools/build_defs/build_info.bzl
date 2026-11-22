# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

"""Generate the server's source metadata from explicit workspace-status inputs."""

_NODE_TOOLCHAIN = "@rules_nodejs//nodejs:toolchain_type"

def _build_info_impl(ctx):
    node = ctx.toolchains[_NODE_TOOLCHAIN].nodeinfo.node
    if node == None:
        fail("GVY build metadata requires the declared Node executable toolchain")
    output = ctx.actions.declare_file(ctx.label.name + "/version.properties")
    args = ctx.actions.args()
    args.add(ctx.file._script)
    args.add("properties")
    args.add("--stable-status", ctx.info_file)
    args.add("--volatile-status", ctx.version_file)
    args.add("--version-source", ctx.file.version_source)
    args.add("--output", output)
    ctx.actions.run(
        executable = node,
        arguments = [args],
        inputs = [ctx.file._script, ctx.file.version_source, ctx.info_file, ctx.version_file],
        tools = [node],
        outputs = [output],
        mnemonic = "GvyBuildInfo",
        progress_message = "Writing GVY source metadata",
    )
    return [DefaultInfo(files = depset([output]))]

gvy_build_info = rule(
    implementation = _build_info_impl,
    attrs = {
        "version_source": attr.label(allow_single_file = True, mandatory = True),
        "_script": attr.label(
            default = Label("//tools/build-info:source-stamp.mjs"),
            allow_single_file = True,
        ),
    },
    toolchains = [_NODE_TOOLCHAIN],
)

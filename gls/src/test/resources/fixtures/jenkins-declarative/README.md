<!--
SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0
-->

# Declarative authoring fixtures

These original fixtures reproduce the three cursor positions in the parameter-authoring audit: a blank entry, `str|`,
and `string(|)`. Each file contains one `/*caret*/` marker; consumers remove it before opening the document. The files
are editing snapshots, so incomplete calls are intentional. They are not oracle-accepted Pipeline claims.

`JenkinsAuthoringBaselineTest` exercises the server through its in-memory JSON-RPC transport. The editor integration
suite reads these same files and accepts the partial entry through the real suggest widget. Provider API results, widget
screenshots and accepted text are separate evidence. Set `GVY_JENKINS_EVIDENCE_DIR` to an existing private absolute
directory to retain the raw framed client/server LSP byte streams (`.lsp`) and editor observations (`.json`); leave it
unset for ordinary tests. The streams preserve transport-assigned request IDs and `Content-Length` headers. Never commit
raw captures or private paths. The captures identify their layer and source; they do not identify an installed
controller or establish Jenkins compatibility.

The [delivery record](../../../../../../kb/JENKINS_DECLARATIVE_DELIVERY_PLAN.md) owns remaining gates.

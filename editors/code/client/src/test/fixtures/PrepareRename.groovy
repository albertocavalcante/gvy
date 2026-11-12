// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

class EditorPrepareRename {
    /* 😀😀 */ def call(value, copy = value) { value }
    @groovy.transform.Memoized
    String memo(String value) { value }
    def untouched() { /* value */ 'value' }
}

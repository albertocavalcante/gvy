// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package editor.rename

class EditorRenameMemoizedFixture {
    @groovy.transform.Memoized
    String memoized(String value) { value }

    String untouched(String value) { value }
}

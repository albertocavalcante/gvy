// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package editor.rename

interface EditorRenameParameterContract<T> {
    T convert(T value)
}

interface EditorRenameParameterChild<U> extends EditorRenameParameterContract<U> {}

class EditorRenameParameterFixture implements EditorRenameParameterChild<String> {
    String convert(String value) { value }

    String untouched(String value) { value }
}

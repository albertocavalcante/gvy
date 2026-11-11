package editor.rename

class EditorRenameMemoizedFixture {
    @groovy.transform.Memoized
    String memoized(String value) { value }

    String untouched(String value) { value }
}

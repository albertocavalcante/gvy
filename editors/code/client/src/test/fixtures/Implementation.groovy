package editor.implementation

interface EditorGreeter {
    String greet()
}

class FriendlyEditorGreeter implements EditorGreeter {
    String greet() { 'hello' }
}

class UnrelatedEditorGreeter {
    String greet() { 'unrelated' }
}

interface EditorConverter<T> {
    T convert(T value)
}

interface EditorChildConverter<U> extends EditorConverter<U> {}

class EditorStringConverter implements EditorChildConverter<String> {
    String convert(String value) { value }
}

class EditorIntegerConverter implements EditorConverter<Integer> {
    Integer convert(Integer value) { value }
}

class EditorDateConverter implements EditorConverter<java.util.Date> {
    java.sql.Date convert(java.sql.Date value) { value }
    java.util.Date convert(java.util.Date value) { value }
}

interface EditorDefaultConverter<T> {
    default T echo(T value) { value }
}

class EditorDefaultStringConverter implements EditorDefaultConverter<String> {}

class EditorRenameBindings {
    def defaults(value, copy = value) { value }

    @groovy.transform.Memoized
    String memoized(String value) { value }

    String untouched(String value) { value }
}

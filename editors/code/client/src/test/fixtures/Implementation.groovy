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

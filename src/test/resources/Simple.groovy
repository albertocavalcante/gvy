package test

class Simple {
    String name
    int age

    Simple(String name, int age) {
        this.name = name
        this.age = age
    }

    String getName() {
        return name
    }

    void setName(String name) {
        this.name = name
    }

    int getAge() {
        return age
    }

    void greet() {
        println "Hello, I'm ${name} and I'm ${age} years old"
    }

    static void main(String[] args) {
        def person = new Simple("Alice", 30)
        person.greet()
    }
}

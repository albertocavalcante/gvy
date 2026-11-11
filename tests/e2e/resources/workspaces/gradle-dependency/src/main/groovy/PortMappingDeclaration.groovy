package sample

import com.example.testlib.Greeter

class PortMappingDeclaration {
    String greeting(String name) {
        new Greeter(name).greet()
    }
}

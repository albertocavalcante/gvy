// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build;

/** Checks that isolated Jackson module roots supply usable transitive runtimes. */
public final class JacksonRuntimeTest {
    public record Sample(String name, int count) {}

    public static void main(String[] args) throws ReflectiveOperationException {
        // Reflection keeps compiler dependencies from supplying the missing runtime jars.
        Class<?> mapperClass = Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
        Object mapper;
        if (args.length == 1 && args[0].equals("kotlin")) {
            Class<?> moduleClass = Class.forName("com.fasterxml.jackson.databind.Module");
            Class<?> builderClass = Class.forName("com.fasterxml.jackson.module.kotlin.KotlinModule$Builder");
            Class.forName("kotlin.reflect.jvm.ReflectJvmMapping");
            Object module = builderClass.getMethod("build").invoke(builderClass.getConstructor().newInstance());
            mapper = mapperClass.getConstructor().newInstance();
            mapperClass.getMethod("registerModule", moduleClass).invoke(mapper, module);
        } else if (args.length == 1 && args[0].equals("yaml")) {
            Class<?> factoryClass = Class.forName("com.fasterxml.jackson.dataformat.yaml.YAMLFactory");
            Class<?> jsonFactoryClass = Class.forName("com.fasterxml.jackson.core.JsonFactory");
            Object factory = factoryClass.getConstructor().newInstance();
            mapper = mapperClass.getConstructor(jsonFactoryClass).newInstance(factory);
        } else {
            throw new IllegalArgumentException("Expected one runtime root: kotlin or yaml");
        }

        Sample expected = new Sample("gvy", 3);
        String json = (String) mapperClass.getMethod("writeValueAsString", Object.class).invoke(mapper, expected);
        Object actual = mapperClass.getMethod("readValue", String.class, Class.class).invoke(mapper, json, Sample.class);
        if (!expected.equals(actual)) {
            throw new AssertionError("Jackson round trip changed the value: " + actual);
        }
    }

    private JacksonRuntimeTest() {}
}

package com.github.albertocavalcante.gvy.semantics.native

import java.lang.reflect.Executable

/** A reflected method's or constructor's parameter types, each formatted with [render] (#1418). */
fun Executable.displayParameterTypes(render: (Class<*>) -> String = Class<*>::getSimpleName): List<String> =
    parameterTypes.map(render)

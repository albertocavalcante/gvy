package com.github.albertocavalcante.gvy.viz.desktop.state

import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.AbstractPreferences

/** Isolates tests from the user's persistent preferences. */
internal class InMemoryPreferences : AbstractPreferences(null, "") {
    private val values = ConcurrentHashMap<String, String>()

    override fun putSpi(key: String, value: String) {
        values[key] = value
    }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) {
        values.remove(key)
    }
    override fun removeNodeSpi() {
        values.clear()
    }
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = emptyArray()
    override fun childSpi(name: String): AbstractPreferences = error("Unexpected preferences child: $name")
    override fun syncSpi() = Unit
    override fun flushSpi() = Unit
}

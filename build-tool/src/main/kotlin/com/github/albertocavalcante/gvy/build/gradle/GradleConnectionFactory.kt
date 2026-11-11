package com.github.albertocavalcante.gvy.build.gradle

import org.gradle.tooling.ProjectConnection
import java.io.File
import java.nio.file.Path

/**
 * Abstraction over Gradle Tooling API connections to enable deterministic unit tests.
 */
fun interface GradleConnectionFactory {
    fun getConnection(projectDir: Path, gradleUserHomeDir: File?): ProjectConnection
}

/** A connection borrowed for one operation; [close] ends the borrow (it never closes the connection itself). */
class ConnectionLease(val connection: ProjectConnection, private val onRelease: () -> Unit = {}) : AutoCloseable {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}

/**
 * A [GradleConnectionFactory] that can also hold a connection open while it is in use, so an idle sweep never closes
 * a connection mid-operation.
 */
interface LeasingConnectionFactory : GradleConnectionFactory {
    /** Borrows the connection until the returned lease is closed. Callers must close it, also on failure. */
    fun acquire(projectDir: Path, gradleUserHomeDir: File?): ConnectionLease
}

/** Leases from a [LeasingConnectionFactory]; any other factory gets an unmanaged lease around `getConnection`. */
fun GradleConnectionFactory.acquire(projectDir: Path, gradleUserHomeDir: File?): ConnectionLease =
    if (this is LeasingConnectionFactory) {
        acquire(projectDir, gradleUserHomeDir)
    } else {
        ConnectionLease(getConnection(projectDir, gradleUserHomeDir))
    }

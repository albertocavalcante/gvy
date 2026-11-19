// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.maven

import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.spi.connector.transport.Transporter
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import java.io.IOException
import java.io.UncheckedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only one verified lookup's HTTP transports; closing them aborts active socket reads and cleanup drains. */
internal class SourceTransferCancellation {
    private val active = mutableSetOf<Registration>()
    private var cancelled = false

    fun register(transporter: Transporter): Transporter {
        val registration = Registration(transporter)
        val closeImmediately = synchronized(this) {
            if (cancelled) {
                true
            } else {
                active.add(registration)
                false
            }
        }
        if (closeImmediately) registration.close()
        return registration
    }

    fun cancel() {
        val registrations = synchronized(this) {
            cancelled = true
            active.toList().also { active.clear() }
        }
        for (registration in registrations) {
            try {
                registration.close()
            } catch (failure: IOException) {
                // One I/O close failure must not prevent cancellation of the remaining transports.
                logger.warn(failure) { "Could not close cancelled source HTTP transport" }
            } catch (failure: UncheckedIOException) {
                // One I/O close failure must not prevent cancellation of the remaining transports.
                logger.warn(failure) { "Could not close cancelled source HTTP transport" }
            }
        }
    }

    private inner class Registration(private val delegate: Transporter) : Transporter by delegate {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                synchronized(this@SourceTransferCancellation) { active.remove(this) }
                delegate.close()
            }
        }
    }

    companion object {
        val SESSION_KEY: Any = SourceTransferCancellation::class.java
        private val logger = KotlinLogging.logger {}
    }
}

/** Keeps Maven's stock HTTP authentication, proxies, redirects and repository behavior intact. */
internal class CancellableSourceTransporterFactory(private val delegate: TransporterFactory) : TransporterFactory {
    override fun getPriority(): Float = delegate.priority

    override fun newInstance(session: RepositorySystemSession, repository: RemoteRepository): Transporter {
        val transporter = delegate.newInstance(session, repository)
        val cancellation = session.data.get(SourceTransferCancellation.SESSION_KEY) as? SourceTransferCancellation
        return cancellation?.register(transporter) ?: transporter
    }
}

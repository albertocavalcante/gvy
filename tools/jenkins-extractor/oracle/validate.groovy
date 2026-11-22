// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import hudson.init.InitMilestone
import hudson.model.DownloadService
import hudson.model.UsageStatistics
import hudson.util.VersionNumber
import hudson.security.ACL
import hudson.security.SecurityRealm
import hudson.security.HudsonPrivateSecurityRealm
import jenkins.security.ApiTokenProperty
import hudson.model.User
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy
import jenkins.model.Jenkins
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.jenkinsci.plugins.pipeline.modeldefinition.parser.Converter

// Only this maintained harness is executed. Fixture strings go to the upstream converter.
def instance = Jenkins.get()
instance.setNumExecutors(0)
instance.setSlaveAgentPort(-1)
instance.updateCenter.sites.clear()
Thread.start('gvy-declarative-oracle') {
    def securityContext = ACL.as2(ACL.SYSTEM2)
    def home = instance.rootDir
    def report = [ok: false]
    int exitCode = 1
    try {
        long deadline = System.nanoTime() + 120_000_000_000L
        while (instance.initLevel != InitMilestone.COMPLETED) {
            if (System.nanoTime() > deadline) throw new IllegalStateException('Jenkins boot incomplete')
            Thread.sleep(100)
        }
        def request = new JsonSlurper().parse(new File(home, 'request.json'))
        def lock = request.lock
        assert Jenkins.VERSION == lock.core.version
        assert System.getProperty('java.specification.version') == '21'
        assert ManagementFactory.memoryMXBean.heapMemoryUsage.max <= 1024L * 1024L * 1024L
        // Groovy 2.4 cannot generate a valid call site for a Java interface static method.
        // Reflect on the public interface, avoiding implementation-class accessibility assumptions.
        def processHandle = ProcessHandle.class.getMethod('current', new Class[0]).invoke(null, new Object[0])
        long jvmPid = ProcessHandle.class.getMethod('pid', new Class[0]).invoke(processHandle, new Object[0])
        def socketChecks = []
        def assertIsolation = {
            assert instance.numExecutors == 0
            assert instance.slaveAgentPort == -1
            assert instance.items.empty
            assert instance.nodes.empty
            assert instance.queue.items.length == 0
            assert instance.isUseSecurity()
            assert instance.securityRealm.is(SecurityRealm.NO_AUTHENTICATION)
            assert !instance.securityRealm.class.methods.any { it.name in ['doCreateFirstAccount', 'doCreateAccount', 'doCreateAccountFromSetupWizard'] }
            // User.current() may materialize the internal, non-persistable SYSTEM record.
            User.getAll().each { user ->
                assert user.id == ACL.SYSTEM2.name
                assert user.getProperty(HudsonPrivateSecurityRealm.Details) == null
                def tokens = user.getProperty(ApiTokenProperty)
                assert tokens == null || (!tokens.hasLegacyToken() && tokens.tokenList.empty)
            }
            assert !instance.securityRealm.allowsSignup()
            assert instance.authorizationStrategy instanceof FullControlOnceLoggedInAuthorizationStrategy
            assert !instance.authorizationStrategy.isAllowAnonymousRead()
            assert !instance.getACL().hasPermission2(Jenkins.ANONYMOUS2, Jenkins.READ)
            assert !instance.getACL().hasPermission2(Jenkins.ANONYMOUS2, Jenkins.ADMINISTER)
            assert instance.updateCenter.sites.empty
            assert UsageStatistics.DISABLED && !instance.isUsageStatisticsCollected()
            DownloadService.Downloadable.all().each { downloadable ->
                assert downloadable.getDue() > System.currentTimeMillis() + 240_000L
            }
            def socketLog = new File(home, 'socket-check-' + socketChecks.size() + '.json')
            def probe = new ProcessBuilder(request.node as String, new File(home, 'inspect-sockets.mjs').absolutePath, jvmPid.toString())
                .redirectErrorStream(true).redirectOutput(socketLog).start()
            try {
                if (!probe.waitFor(10, TimeUnit.SECONDS)) throw new IllegalStateException('socket inspection timed out')
                assert probe.exitValue() == 0 : socketLog.text
                def checked = new JsonSlurper().parse(socketLog)
                assert checked.pid == jvmPid && checked.internetSockets == 0
                socketChecks.add(checked)
            } finally {
                if (probe.isAlive()) {
                    probe.destroyForcibly()
                    assert probe.waitFor(5, TimeUnit.SECONDS)
                }
            }
        }
        assertIsolation()
        assert instance.pluginManager.failedPlugins.empty
        def plugins = instance.pluginManager.plugins
        assert plugins.collect { it.shortName }.sort() == lock.plugins.collect { it.id }.sort()
        lock.plugins.each { expected ->
            def actual = instance.pluginManager.getPlugin(expected.id)
            assert actual != null && actual.active && actual.enabled
            assert actual.version == expected.version
            assert !new VersionNumber(Jenkins.VERSION).isOlderThan(new VersionNumber(expected.requiredCore))
            expected.dependencies.each { dependency ->
                def installed = instance.pluginManager.getPlugin(dependency.id)
                assert dependency.optional || installed != null
                if (installed != null) {
                    assert !new VersionNumber(installed.version).isOlderThan(new VersionNumber(dependency.minimumVersion))
                }
            }
        }
        def results = request.fixtures.collect { fixture ->
            String actual
            try {
                actual = Converter.scriptToPipelineDef(fixture.source) == null ? 'missing-pipeline' : 'accepted'
            } catch (MultipleCompilationErrorsException rejected) {
                actual = 'rejected'
            }
            assert actual == fixture.expected : 'Unexpected oracle classification: ' + fixture.id
            [id: fixture.id, sha256: fixture.sha256, actual: actual]
        }
        assertIsolation()
        report = [ok: true, isolationVerifiedBeforeAndAfter: true, socketChecks: socketChecks, userIds: User.getAll().collect { it.id }, downloadables: DownloadService.Downloadable.all().collect { [id: it.id, due: it.getDue()] }, core: Jenkins.VERSION, javaVersion: System.getProperty('java.runtime.version'), maxHeapBytes: ManagementFactory.memoryMXBean.heapMemoryUsage.max, jvmArguments: ManagementFactory.runtimeMXBean.inputArguments, plugins: plugins.collect { [id: it.shortName, version: it.version] }.sort { it.id }, results: results]
        exitCode = 0
    } catch (Throwable failure) {
        report = [ok: false, failure: failure.class.name, message: failure.message]
        failure.printStackTrace()
        // Keep bounded private diagnostics for unexpected background work; never turn a failed guard into a pass.
        Thread.getAllStackTraces().entrySet().take(128).each { entry ->
            System.err.println('oracle failure thread: ' + entry.key.name + ' [' + entry.key.state + ']')
            entry.value.take(32).each { frame -> System.err.println('    at ' + frame) }
        }
    } finally {
        new File(home, 'result.json').setText(JsonOutput.toJson(report), 'UTF-8')
        securityContext.close()
        System.exit(exitCode)
    }
}

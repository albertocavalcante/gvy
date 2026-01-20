import org.example.BuildHelper

/**
 * Build a Jenkins plugin using Maven.
 *
 * @param params Map of configuration options
 * @param params.forkCount Number of parallel forks (default: 2)
 * @param params.skipTests Skip test execution (default: false)
 */
def call(Map params = [:]) {
    def helper = new BuildHelper()
    def forkCount = params.forkCount ?: 2
    def skipTests = params.skipTests ?: false

    echo "Building plugin with forkCount: ${forkCount}"

    if (!skipTests) {
        helper.runTests()
    }

    helper.runBuild()
}

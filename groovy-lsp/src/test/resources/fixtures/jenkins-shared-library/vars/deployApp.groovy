/**
 * Deploy application to specified environment.
 *
 * @param params Map with deployment configuration
 * @param params.env Target environment (staging, production)
 * @param params.dryRun If true, simulate deployment (default: false)
 */
def call(Map params) {
    def env = params.env ?: 'production'
    def dryRun = params.dryRun ?: false

    echo "Deploying to ${env} environment"

    if (dryRun) {
        echo "[DRY-RUN] Would deploy to ${env}"
    } else {
        sh "deploy.sh --env=${env}"
    }
}

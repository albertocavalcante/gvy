/**
 * Infrastructure utilities for Jenkins pipelines.
 * Note: This file intentionally has NO companion .txt file to test graceful handling.
 */

def checkoutSCM() {
    checkout scm
}

def withDockerBuild(String image = 'maven:3.9', Closure body) {
    docker.image(image).inside {
        body()
    }
}

def notifySlack(String message, String channel = '#builds') {
    slackSend(channel: channel, message: message)
}

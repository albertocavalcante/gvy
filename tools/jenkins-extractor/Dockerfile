# Jenkins GDSL Extractor
#
# This Dockerfile creates a Jenkins instance pre-configured with pipeline plugins
# for extracting GDSL metadata via the /pipeline-syntax/gdsl endpoint.
#
# Usage:
#   docker build -t jenkins-extractor .
#   docker run -d -p 8080:8080 --name jenkins-extractor jenkins-extractor
#   # Wait for startup, then:
#   curl http://localhost:8080/pipeline-syntax/gdsl > output.gdsl

FROM jenkins/jenkins:lts-jdk21

# Skip setup wizard
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

# Copy plugins list
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt

# Install plugins using the plugin installation manager
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# Allow anonymous read access for metadata extraction
# This creates a groovy init script that configures security
COPY --chown=jenkins:jenkins init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/

# Expose Jenkins port
EXPOSE 8080 50000

# Health check
HEALTHCHECK --interval=10s --timeout=5s --retries=30 \
    CMD curl -f http://localhost:8080/api/json || exit 1


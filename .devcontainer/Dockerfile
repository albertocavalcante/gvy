ARG BASE_IMAGE=registry.access.redhat.com/ubi9/ubi-minimal
ARG BASE_IMAGE_TAG=latest

FROM ${BASE_IMAGE}:${BASE_IMAGE_TAG}

ARG JAVA_VERSION=17
ARG USERNAME=vscode
ARG USER_UID=1000
ARG USER_GID=$USER_UID

USER root

# -----------------------------------------------------------------------------
# Layer 1: Bootstrap Dependencies
# Install minimal tools needed for downloading artifacts (curl, checksums).
# This layer is highly stable and rarely changes.
# -----------------------------------------------------------------------------
RUN microdnf update -y \
    && microdnf reinstall -y curl-minimal \
    && microdnf install -y unzip \
    && microdnf clean all \
    && rm -rf /var/cache/yum

# -----------------------------------------------------------------------------
# Layer 2: Kotlin LSP VSIX
# Download and cache the VSIX.
# Placed BEFORE the main package install so that adding dev tools (like make/jq)
# does not trigger a re-download of this artifact.
# -----------------------------------------------------------------------------
# See: https://github.com/Kotlin/kotlin-lsp/releases
ARG KOTLIN_LSP_VERSION=0.253.10629
ARG KOTLIN_LSP_SHA256=638ec1552fde7e5158f8c7df06443cfb97c5c09957273790586cbd59115bf11c

# Copy local cache if available (from .devcontainer/cache/)
COPY cache/. /tmp/cache/

RUN if [ -f "/tmp/cache/kotlin-${KOTLIN_LSP_VERSION}.vsix" ]; then \
        echo "Using local cache for Kotlin LSP VSIX..."; \
        cp "/tmp/cache/kotlin-${KOTLIN_LSP_VERSION}.vsix" /tmp/kotlin.vsix; \
    else \
        echo "Downloading Kotlin LSP VSIX..."; \
        curl -fSL -o /tmp/kotlin.vsix https://download-cdn.jetbrains.com/kotlin-lsp/${KOTLIN_LSP_VERSION}/kotlin-${KOTLIN_LSP_VERSION}.vsix; \
    fi \
    # Verify checksum
    && echo "${KOTLIN_LSP_SHA256}  /tmp/kotlin.vsix" | sha256sum -c - \
    # Verify it's a valid zip file (VSIX is a zip) to detect corruption early
    && unzip -t /tmp/kotlin.vsix \
    && mkdir -p /usr/local/share/vscode-extensions \
    # NB: Record build timestamp for debugging purposes
    && date > /usr/local/share/vscode-extensions/.build_time \
    && mv /tmp/kotlin.vsix /usr/local/share/vscode-extensions/kotlin.vsix \
    && chmod 644 /usr/local/share/vscode-extensions/kotlin.vsix \
    && rm -rf /tmp/cache /tmp/kotlin.vsix

# -----------------------------------------------------------------------------
# Layer 3: Java Toolchain (Heavy / Stable)
# Installed first so that adding lightweight tools below doesn't trigger a JDK reinstall.
# -----------------------------------------------------------------------------
RUN microdnf install -y --nodocs \
        java-${JAVA_VERSION}-openjdk-devel \
    && microdnf clean all \
    && rm -rf /var/cache/yum

# -----------------------------------------------------------------------------
# Layer 4: Base Development Tools (Light / Volatile)
# Install small utilities. This layer is fast to rebuild.
# -----------------------------------------------------------------------------
RUN microdnf install -y --nodocs \
        shadow-utils \
        sudo \
        git \
        make \
        zip \
        procps-ng \
        findutils \
        tar \
        gzip \
        vim \
    && microdnf clean all \
    && rm -rf /var/cache/yum

# -----------------------------------------------------------------------------
# Layer 5: User Setup
# -----------------------------------------------------------------------------
RUN groupadd --gid $USER_GID $USERNAME \
    && useradd --uid $USER_UID --gid $USER_GID -m $USERNAME \
    && echo $USERNAME ALL=\(root\) NOPASSWD:ALL > /etc/sudoers.d/$USERNAME \
    && chmod 0440 /etc/sudoers.d/$USERNAME

USER $USERNAME
WORKDIR /home/$USERNAME

# Set JAVA_HOME (path may vary slightly but this is standard for RHEL OpenJDK)
ENV JAVA_HOME=/usr/lib/jvm/java-${JAVA_VERSION}-openjdk
ENV PATH=${JAVA_HOME}/bin:${PATH}

CMD ["/bin/bash"]

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
        util-linux-user \
        glibc-langpack-en \
    && microdnf clean all \
    && rm -rf /var/cache/yum

# -----------------------------------------------------------------------------
# Layer 5: Modern CLI Tools (rg, fd, bat, fzf, jq)
# High-performance replacements for standard tools.
# -----------------------------------------------------------------------------
ARG JQ_VERSION=1.8.1
ARG RG_VERSION=15.1.0
ARG FD_VERSION=10.3.0
ARG BAT_VERSION=0.26.0
ARG FZF_VERSION=0.67.0

# SHA256 Checksums (x86_64 / aarch64)
ARG JQ_SHA256_X64=020468de7539ce70ef1bceaf7cde2e8c4f2ca6c3afb84642aabc5c97d9fc2a0d
ARG JQ_SHA256_ARM64=6bc62f25981328edd3cfcfe6fe51b073f2d7e7710d7ef7fcdac28d4e384fc3d4

ARG RG_SHA256_X64=1c9297be4a084eea7ecaedf93eb03d058d6faae29bbc57ecdaf5063921491599
ARG RG_SHA256_ARM64=2b661c6ef508e902f388e9098d9c4c5aca72c87b55922d94abdba830b4dc885e

ARG FD_SHA256_X64=2b6bfaae8c48f12050813c2ffe1884c61ea26e750d803df9c9114550a314cd14
ARG FD_SHA256_ARM64=66f297e404400a3358e9a0c0b2f3f4725956e7e4435427a9ae56e22adbe73a68

ARG BAT_SHA256_X64=26a683b038fbc526c6df4166db693ddc9e431e5bdc285d45ccd6b72f3cff5ffd
ARG BAT_SHA256_ARM64=eb8c96a2d9aaaf81cf7d8c3b7918a7336a06a1dec08878887963dc2cdd7db354

ARG FZF_SHA256_X64=4be08018ca37b32518c608741933ea335a406de3558242b60619e98f25be2be1
ARG FZF_SHA256_ARM64=7071f48c2ac0f2bc992d6d33cc36fd675a579a98cc976dda699eea07dd5e9c58

ARG STARSHIP_VERSION=1.24.1
ARG STARSHIP_SHA256_X64=44a729c34aea5b0451fba49108cdc5ef6b1ae68db65e7623cc244a52efcd23d1
ARG STARSHIP_SHA256_ARM64=a01ac37aa5993b78ecd6761c5ff4f805032b8ba566357ffbb2227980216f2216

ARG GH_VERSION=2.83.1
ARG GH_SHA256_X64=1c5252d4ce3db07b51c01ff0b909583da6364ff3fdc06d0c2e75e62dc0380a34
ARG GH_SHA256_ARM64=36af472f8e96fd67253f813f71d9610624cc4e3c7a5b148459e42ffa1a933f64

RUN ARCH=$(uname -m) \
    && case $ARCH in \
        x86_64) \
            RUST_ARCH="x86_64-unknown-linux-musl"; JQ_ARCH="linux-amd64"; FZF_ARCH="linux_amd64"; \
            JQ_SHA256=$JQ_SHA256_X64; RG_SHA256=$RG_SHA256_X64; FD_SHA256=$FD_SHA256_X64; \
            BAT_SHA256=$BAT_SHA256_X64; FZF_SHA256=$FZF_SHA256_X64; \
            STARSHIP_SHA256=$STARSHIP_SHA256_X64; GH_SHA256=$GH_SHA256_X64; \
            GH_ARCH="linux_amd64"; \
            ;; \
        aarch64) \
            RUST_ARCH="aarch64-unknown-linux-gnu"; JQ_ARCH="linux-arm64"; FZF_ARCH="linux_arm64"; \
            JQ_SHA256=$JQ_SHA256_ARM64; RG_SHA256=$RG_SHA256_ARM64; FD_SHA256=$FD_SHA256_ARM64; \
            BAT_SHA256=$BAT_SHA256_ARM64; FZF_SHA256=$FZF_SHA256_ARM64; \
            STARSHIP_SHA256=$STARSHIP_SHA256_ARM64; GH_SHA256=$GH_SHA256_ARM64; \
            GH_ARCH="linux_arm64"; \
            ;; \
        *) echo "Unsupported arch: $ARCH"; exit 1; ;; \
    esac \
    # JQ
    && curl -fL -o /usr/local/bin/jq https://github.com/jqlang/jq/releases/download/jq-${JQ_VERSION}/jq-${JQ_ARCH} \
    && echo "$JQ_SHA256  /usr/local/bin/jq" | sha256sum -c - \
    && chmod +x /usr/local/bin/jq \
    # Ripgrep
    && curl -fL -o /tmp/rg.tar.gz https://github.com/BurntSushi/ripgrep/releases/download/${RG_VERSION}/ripgrep-${RG_VERSION}-${RUST_ARCH}.tar.gz \
    && echo "$RG_SHA256  /tmp/rg.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/rg.tar.gz -C /tmp \
    && mv /tmp/ripgrep-${RG_VERSION}-${RUST_ARCH}/rg /usr/local/bin/ \
    # FD
    && curl -fL -o /tmp/fd.tar.gz https://github.com/sharkdp/fd/releases/download/v${FD_VERSION}/fd-v${FD_VERSION}-${RUST_ARCH}.tar.gz \
    && echo "$FD_SHA256  /tmp/fd.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/fd.tar.gz -C /tmp \
    && mv /tmp/fd-v${FD_VERSION}-${RUST_ARCH}/fd /usr/local/bin/ \
    # Bat
    && curl -fL -o /tmp/bat.tar.gz https://github.com/sharkdp/bat/releases/download/v${BAT_VERSION}/bat-v${BAT_VERSION}-${RUST_ARCH}.tar.gz \
    && echo "$BAT_SHA256  /tmp/bat.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/bat.tar.gz -C /tmp \
    && mv /tmp/bat-v${BAT_VERSION}-${RUST_ARCH}/bat /usr/local/bin/ \
    # FZF
    && curl -fL -o /tmp/fzf.tar.gz https://github.com/junegunn/fzf/releases/download/v${FZF_VERSION}/fzf-${FZF_VERSION}-${FZF_ARCH}.tar.gz \
    && echo "$FZF_SHA256  /tmp/fzf.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/fzf.tar.gz -C /usr/local/bin/ \
    # Starship
    # Note: Starship musl builds work on both glibc and musl distros (static binary)
    # We use -unknown-linux-musl for both arches as that is what they provide for static linking
    && STARSHIP_ARCH=${RUST_ARCH/-gnu/-musl} \
    && curl -fL -o /tmp/starship.tar.gz https://github.com/starship/starship/releases/download/v${STARSHIP_VERSION}/starship-${STARSHIP_ARCH}.tar.gz \
    && echo "$STARSHIP_SHA256  /tmp/starship.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/starship.tar.gz -C /usr/local/bin/ \
    # GitHub CLI
    && curl -fL -o /tmp/gh.tar.gz https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_${GH_ARCH}.tar.gz \
    && echo "$GH_SHA256  /tmp/gh.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/gh.tar.gz -C /tmp \
    && mv /tmp/gh_${GH_VERSION}_${GH_ARCH}/bin/gh /usr/local/bin/ \
    && mkdir -p /usr/local/share/man/man1 \
    && mv /tmp/gh_${GH_VERSION}_${GH_ARCH}/share/man/man1/gh.1 /usr/local/share/man/man1/ \
    # Cleanup
    && rm -rf /tmp/*

# -----------------------------------------------------------------------------
# Layer 6: User Setup
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

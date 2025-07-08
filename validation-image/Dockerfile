FROM ghcr.io/netcracker/qubership/core-base:1.1.0
LABEL maintainer="qubership"

USER root

RUN apk add --no-cache zip unzip jq postgresql wget python3 py3-pycryptodome && \
    curl -L "https://dl.k8s.io/release/v1.29.0/bin/linux/amd64/kubectl" -o /bin/kubectl && \
    chmod +x /bin/kubectl

COPY --chown=10001:0 --chmod=755 validation.sh /validation.sh
COPY --chown=10001:0 --chmod=755 prepare-database.sh /prepare-database.sh

USER 10001:10001
ENTRYPOINT ["/validation.sh"]

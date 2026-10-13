FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.7@sha256:f0836415633bc2a63c2a0431c4c0701d17bd7b31132176b26651b49927d9ad7e
LABEL maintainer="qubership"

USER root

RUN apk add --no-cache zip unzip jq postgresql wget python3 py3-pycryptodome kubectl

COPY --chown=10001:0 --chmod=755 validation.sh /validation.sh
COPY --chown=10001:0 --chmod=755 prepare-database.sh /prepare-database.sh

USER 10001:10001
ENTRYPOINT ["/validation.sh"]

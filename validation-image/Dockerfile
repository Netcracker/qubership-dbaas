FROM ghcr.io/netcracker/qubership-core-base:2.4.0@sha256:803d5b5411835a2f46d577d6fb6810e4a11f9a954a12993e705f2a912db2621d
LABEL maintainer="qubership"

USER root

RUN apk add --no-cache zip unzip jq postgresql wget python3 py3-pycryptodome kubectl

COPY --chown=10001:0 --chmod=755 validation.sh /validation.sh
COPY --chown=10001:0 --chmod=755 prepare-database.sh /prepare-database.sh

USER 10001:10001
ENTRYPOINT ["/validation.sh"]

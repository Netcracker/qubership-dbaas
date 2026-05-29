FROM ghcr.io/netcracker/qubership-core-base:2.2.13@sha256:39304438e598101578f91d84bc58699fd1a3f717a46b786cb43523735826e7b4
LABEL maintainer="qubership"

USER root

RUN apk add --no-cache zip unzip jq postgresql wget python3 py3-pycryptodome kubectl

COPY --chown=10001:0 --chmod=755 validation.sh /validation.sh
COPY --chown=10001:0 --chmod=755 prepare-database.sh /prepare-database.sh

USER 10001:10001
ENTRYPOINT ["/validation.sh"]

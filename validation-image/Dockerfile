FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.10@sha256:51173b982248bb866b236f504579b71c0a9c4a41975b957595f9faa9ab3fb5d6
LABEL maintainer="qubership"

USER root

RUN apk add --no-cache zip unzip jq postgresql wget python3 py3-pycryptodome kubectl

COPY --chown=10001:0 --chmod=755 validation.sh /validation.sh
COPY --chown=10001:0 --chmod=755 prepare-database.sh /prepare-database.sh

USER 10001:10001
ENTRYPOINT ["/validation.sh"]

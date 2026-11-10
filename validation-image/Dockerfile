FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.12@sha256:2eaad09b552eca2d10d65570f66425322235898ac8d7299eddbad8fdfa81b66b
LABEL maintainer="qubership"

USER root

RUN apk add --no-cache zip unzip jq postgresql wget python3 py3-pycryptodome kubectl

COPY --chown=10001:0 --chmod=755 validation.sh /validation.sh
COPY --chown=10001:0 --chmod=755 prepare-database.sh /prepare-database.sh

USER 10001:10001
ENTRYPOINT ["/validation.sh"]

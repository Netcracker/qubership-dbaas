FROM ghcr.io/netcracker/qubership-core-base:2.3.0@sha256:3ef9a4b348dcf26d1e9f63d375209c9b4b2359e0080fbcab1a566f6f6291b789
LABEL maintainer="qubership"

USER root

RUN apk add --no-cache zip unzip jq postgresql wget python3 py3-pycryptodome kubectl

COPY --chown=10001:0 --chmod=755 validation.sh /validation.sh
COPY --chown=10001:0 --chmod=755 prepare-database.sh /prepare-database.sh

USER 10001:10001
ENTRYPOINT ["/validation.sh"]

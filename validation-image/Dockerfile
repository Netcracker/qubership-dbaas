FROM ghcr.io/netcracker/qubership-core-base:2.3.1@sha256:541d3ace16c310fed889ef0cfb23d62f810dd9879fb4086c0d8a37694b01d364
LABEL maintainer="qubership"

USER root

RUN apk add --no-cache zip unzip jq postgresql wget python3 py3-pycryptodome kubectl

COPY --chown=10001:0 --chmod=755 validation.sh /validation.sh
COPY --chown=10001:0 --chmod=755 prepare-database.sh /prepare-database.sh

USER 10001:10001
ENTRYPOINT ["/validation.sh"]

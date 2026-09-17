FROM ghcr.io/netcracker/qubership-core-base:2.5.3@sha256:51063717c4536e8e6ee7afc2d1e0bee6f4c12237e818d6112cf55e149d6cbeca
LABEL maintainer="qubership"

USER root

RUN apk add --no-cache zip unzip jq postgresql wget python3 py3-pycryptodome kubectl

COPY --chown=10001:0 --chmod=755 validation.sh /validation.sh
COPY --chown=10001:0 --chmod=755 prepare-database.sh /prepare-database.sh

USER 10001:10001
ENTRYPOINT ["/validation.sh"]

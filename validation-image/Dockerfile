FROM ghcr.io/netcracker/qubership-core-base:2.3.5@sha256:ce0f2451c96c254e58c54600b58aa323b6582e38a38cbdd5ce6b0e071b32b700
LABEL maintainer="qubership"

USER root

RUN apk add --no-cache zip unzip jq postgresql wget python3 py3-pycryptodome kubectl

COPY --chown=10001:0 --chmod=755 validation.sh /validation.sh
COPY --chown=10001:0 --chmod=755 prepare-database.sh /prepare-database.sh

USER 10001:10001
ENTRYPOINT ["/validation.sh"]

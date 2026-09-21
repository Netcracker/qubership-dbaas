FROM ghcr.io/netcracker/qubership-core-base:2.5.4@sha256:9e9c011ea0671546ae74d0a3b6e8740a8098087ddd98bd9a7b02d9ab802c9ba6
LABEL maintainer="qubership"

USER root

RUN apk add --no-cache zip unzip jq postgresql wget python3 py3-pycryptodome kubectl

COPY --chown=10001:0 --chmod=755 validation.sh /validation.sh
COPY --chown=10001:0 --chmod=755 prepare-database.sh /prepare-database.sh

USER 10001:10001
ENTRYPOINT ["/validation.sh"]

FROM ghcr.io/netcracker/qubership-core-base:2.5.0@sha256:d81cd148d874afea869c540defc632ff0bba36d90c809eaec3db0fcdd82ac7a5
LABEL maintainer="qubership"

USER root

RUN apk add --no-cache zip unzip jq postgresql wget python3 py3-pycryptodome kubectl

COPY --chown=10001:0 --chmod=755 validation.sh /validation.sh
COPY --chown=10001:0 --chmod=755 prepare-database.sh /prepare-database.sh

USER 10001:10001
ENTRYPOINT ["/validation.sh"]

# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

FROM --platform=$BUILDPLATFORM golang:1.27@sha256:2669ec97bbb6e54602952b4171211116e3fe90e5f9c47619920c74554b3d7d09 AS builder
ARG TARGETOS
ARG TARGETARCH
ENV GOTOOLCHAIN=auto

WORKDIR /app
COPY go.mod ./
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go test ./internal/... && \
    CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-availability-probe ./cmd/probe

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.9@sha256:ab860790e4ecce56f8df673495cd5745ce587fffa7d7eb210d6d098563ccb26d
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-availability-probe /app/dbaas-availability-probe
USER 10001:10001
ENTRYPOINT ["/app/dbaas-availability-probe"]

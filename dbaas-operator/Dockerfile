# Build the manager binary
FROM --platform=$BUILDPLATFORM golang:1.26 AS builder
ARG TARGETOS
ARG TARGETARCH

WORKDIR /app
# Copy the Go Modules manifests
COPY go.mod go.mod
COPY go.sum go.sum
# cache deps before building and copying source so that we don't need to re-download as much
# and so that source changes don't invalidate our downloaded layer
RUN go mod download

# Copy the Go source (relies on .dockerignore to filter)
COPY . .

# Build
# The builder stage always runs on the build host platform (BUILDPLATFORM) to avoid QEMU emulation.
# Cross-compilation is handled natively by Go via TARGETOS/TARGETARCH, which BuildKit sets automatically.
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} go build -a -o dbaas-operator ./cmd/

# Use distroless as minimal base image to package the manager binary
# Refer to https://github.com/Netcracker/qubership-core-base-images/pkgs/container/qubership-core-base for more details
FROM ghcr.io/netcracker/qubership-core-base:latest
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-operator /app/dbaas-operator
USER 10001:10001

ENTRYPOINT ["/app/dbaas-operator"]

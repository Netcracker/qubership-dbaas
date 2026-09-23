FROM --platform=$BUILDPLATFORM golang:1.27@sha256:3680233e3204827fbdc66088528ae6d4b3d034f51d03a99d454f6de034888244 AS builder
ARG TARGETOS
ARG TARGETARCH
# Allow Go toolchain auto-download when the image patch (e.g. 1.26.1) is
# older than the minimum declared in go.mod (e.g. 1.26.2).
ENV GOTOOLCHAIN=auto

WORKDIR /app
COPY go.mod go.mod
COPY go.sum go.sum
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download

# Copy the Go source (relies on .dockerignore to filter)
COPY . .

# The builder stage always runs on the build host platform (BUILDPLATFORM) to avoid QEMU emulation.
# Cross-compilation is handled natively by Go via TARGETOS/TARGETARCH, which BuildKit sets automatically.
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-operator ./cmd/

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.5@sha256:1e207c43e907165f9c55838bb853465357a28bfd50799cf4aa66b959fe72a836
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-operator /app/dbaas-operator
USER 10001:10001

CMD ["/app/dbaas-operator"]

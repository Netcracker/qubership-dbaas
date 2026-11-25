FROM --platform=$BUILDPLATFORM golang:1.27@sha256:5f1b98fa02fe396ebce2a0227a0e08309e11792915b9dd2e1f71053d7e7e6666 AS builder
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

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.6.1@sha256:7be670fc57c243195c63597106413e1b0365381c2d186a71cb6d12fcffc2278b
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-operator /app/dbaas-operator
USER 10001:10001

CMD ["/app/dbaas-operator"]

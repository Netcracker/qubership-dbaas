FROM --platform=$BUILDPLATFORM golang:1.26@sha256:b900de91b15b2e2953d930ece1d0ecff0a1590ab2006088d20dcf0f56f1e979f AS builder
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

FROM ghcr.io/netcracker/qubership-core-base:2.3.4@sha256:55b2de47ca9fd85127a2077c65b9187f04ffd41849b2460f49c71b7e6372f940
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-operator /app/dbaas-operator
USER 10001:10001

CMD ["/app/dbaas-operator"]

# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

FROM --platform=$BUILDPLATFORM golang:1.27@sha256:0ecdc2a9f6156af6451080bfe3d8382a662fcc4e209608c6f919e643453514c1 AS builder
ARG TARGETOS
ARG TARGETARCH
ENV GOTOOLCHAIN=auto

WORKDIR /app
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download

COPY . .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o go-test-app-service .

FROM ghcr.io/netcracker/qubership-core-base:2.4.0@sha256:803d5b5411835a2f46d577d6fb6810e4a11f9a954a12993e705f2a912db2621d
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/go-test-app-service /app/go-test-app-service
USER 10001:10001
EXPOSE 8080
CMD ["/app/go-test-app-service"]

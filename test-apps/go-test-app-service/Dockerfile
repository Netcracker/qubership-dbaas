# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

FROM --platform=$BUILDPLATFORM golang:1.27@sha256:820153096fda4de6ac1b7194d12e416b7dd8f4e9a33d17e0345b111b945162d8 AS builder
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

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.8@sha256:0c2c9e4b49e73efffb30edf8d3de8b3eb43194a6c4f688da08ec3236b570b486
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/go-test-app-service /app/go-test-app-service
USER 10001:10001
EXPOSE 8080
CMD ["/app/go-test-app-service"]

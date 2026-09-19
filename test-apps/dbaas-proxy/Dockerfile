# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

FROM --platform=$BUILDPLATFORM golang:1.27@sha256:3680233e3204827fbdc66088528ae6d4b3d034f51d03a99d454f6de034888244 AS builder
ARG TARGETOS
ARG TARGETARCH
ENV GOTOOLCHAIN=auto

WORKDIR /app
COPY go.mod ./
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-proxy .

FROM ghcr.io/netcracker/qubership-core-base:2.5.3@sha256:51063717c4536e8e6ee7afc2d1e0bee6f4c12237e818d6112cf55e149d6cbeca
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-proxy /app/dbaas-proxy
USER 10001:10001
EXPOSE 8080
CMD ["/app/dbaas-proxy"]

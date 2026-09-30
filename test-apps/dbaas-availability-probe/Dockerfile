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
    go test ./internal/... && \
    CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-availability-probe ./cmd/probe

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.6@sha256:e85e971e33cdb218bf1c23ccbad0d0ddaee7d3abfc442247d81081e3d0467467
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-availability-probe /app/dbaas-availability-probe
USER 10001:10001
ENTRYPOINT ["/app/dbaas-availability-probe"]

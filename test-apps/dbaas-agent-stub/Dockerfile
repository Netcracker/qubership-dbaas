# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

FROM --platform=$BUILDPLATFORM golang:1.27@sha256:f42f8545265b7fe4124ecdd50a7778c15d5e3fc4d0af648e508e4f4c6a4c572b AS builder
ARG TARGETOS
ARG TARGETARCH
ENV GOTOOLCHAIN=auto

WORKDIR /app
COPY go.mod ./
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-agent-stub .

FROM ghcr.io/netcracker/qubership-core-base:2.3.7@sha256:b917b3a1731a2ae26b507d22565f030ec25ff8d28b75a80b8b08bbc946f4d73b
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-agent-stub /app/dbaas-agent-stub
USER 10001:10001
EXPOSE 8080
CMD ["/app/dbaas-agent-stub"]

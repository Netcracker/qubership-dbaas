FROM golang:1.27@sha256:f42f8545265b7fe4124ecdd50a7778c15d5e3fc4d0af648e508e4f4c6a4c572b AS builder
ARG TARGETOS
ARG TARGETARCH

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-aggregator ./dev/aggregator-mock/main.go

FROM ghcr.io/netcracker/qubership-core-base:latest@sha256:b917b3a1731a2ae26b507d22565f030ec25ff8d28b75a80b8b08bbc946f4d73b
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-aggregator /app/dbaas-aggregator
USER 10001:10001

ENTRYPOINT ["/app/dbaas-aggregator"]

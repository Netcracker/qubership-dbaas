FROM golang:1.27@sha256:f44f6e88636cfb311f9ebace870ded69d943f227bb3cb27d32ffd84ea18c43ea AS builder
ARG TARGETOS
ARG TARGETARCH

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-aggregator ./dev/aggregator-mock/main.go

FROM ghcr.io/netcracker/qubership-core-base:latest@sha256:346237ade5d4a91a2ba3099aa9377e744b40241b105bc580fb48ad81f56137a1
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-aggregator /app/dbaas-aggregator
USER 10001:10001

ENTRYPOINT ["/app/dbaas-aggregator"]

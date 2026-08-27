FROM golang:1.27@sha256:0ecdc2a9f6156af6451080bfe3d8382a662fcc4e209608c6f919e643453514c1 AS builder
ARG TARGETOS
ARG TARGETARCH

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-aggregator ./dev/aggregator-mock/main.go

FROM ghcr.io/netcracker/qubership-core-base:latest@sha256:c668333b6b03d897bfea3a7345bcff14a3b9224fffe62024202b2a125a6b0171
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-aggregator /app/dbaas-aggregator
USER 10001:10001

ENTRYPOINT ["/app/dbaas-aggregator"]

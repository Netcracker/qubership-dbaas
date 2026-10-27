FROM golang:1.27@sha256:9048d5e7751706200eb1719c7dae0436950d362f29a1da91fa05e5c63867c60f AS builder
ARG TARGETOS
ARG TARGETARCH

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-aggregator ./dev/aggregator-mock/main.go

FROM ghcr.io/netcracker/qubership-core-base:alpine-latest@sha256:0c2c9e4b49e73efffb30edf8d3de8b3eb43194a6c4f688da08ec3236b570b486
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-aggregator /app/dbaas-aggregator
USER 10001:10001

ENTRYPOINT ["/app/dbaas-aggregator"]

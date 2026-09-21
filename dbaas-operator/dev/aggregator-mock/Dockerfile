FROM golang:1.27@sha256:3680233e3204827fbdc66088528ae6d4b3d034f51d03a99d454f6de034888244 AS builder
ARG TARGETOS
ARG TARGETARCH

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -a -o dbaas-aggregator ./dev/aggregator-mock/main.go

FROM ghcr.io/netcracker/qubership-core-base:latest@sha256:5c4891cac7f8dccc97d9eff9df93e2bd13234b1f436e7171bcda10e1d85f45f2
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/dbaas-aggregator /app/dbaas-aggregator
USER 10001:10001

ENTRYPOINT ["/app/dbaas-aggregator"]

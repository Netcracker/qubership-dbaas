package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"github.com/netcracker/qubership-core-lib-go/v3/configloader"
	"github.com/netcracker/qubership-core-lib-go/v3/security"
	"github.com/netcracker/qubership-core-lib-go/v3/serviceloader"

	"github.com/netcracker/qubership-dbaas/test-apps/go-test-app-service/internal/app"
)

const defaultListenAddress = ":8080"

func init() {
	serviceloader.Register(2, &security.DummyToken{})
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	configloader.Init(configloader.EnvPropertySource())
	application := app.New()

	addr := os.Getenv("HTTP_ADDR")
	if addr == "" {
		addr = defaultListenAddress
	}

	server := &http.Server{
		Addr:              addr,
		Handler:           application.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Printf("go-test-app-service listening on %s", addr)
	return server.ListenAndServe()
}

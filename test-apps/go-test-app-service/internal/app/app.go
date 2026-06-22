package app

import (
	"errors"
	"net/http"

	dbaasbase "github.com/netcracker/qubership-core-lib-go-dbaas-base-client/v3"
	pgdbaas "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4"
	pgmodel "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4/model"

	"github.com/netcracker/qubership-dbaas/test-apps/go-test-app-service/internal/postgresmigrations"
)

type App struct {
	pgDatabase pgdbaas.Database
}

func New() *App {
	pool := dbaasbase.NewDbaaSPool()
	pgClient := pgdbaas.NewClient(pool)
	pgDatabase := pgClient.ServiceDatabase(pgmodel.DbParams{Migrations: postgresmigrations.Migrations()})
	return &App{pgDatabase: pgDatabase}
}

func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", handleHealth)
	mux.HandleFunc("/postgres/ping", a.handlePostgresPing)
	mux.HandleFunc("/postgres/connection-properties", a.handlePostgresConnectionProperties)
	mux.HandleFunc("/postgres/items", a.handlePostgresItems)
	return mux
}

func handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, errors.New("method not allowed"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

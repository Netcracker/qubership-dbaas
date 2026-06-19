package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	dbaasbase "github.com/netcracker/qubership-core-lib-go-dbaas-base-client/v3"
	pgdbaas "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4"
	pgmodel "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4/model"
	"github.com/netcracker/qubership-core-lib-go/v3/configloader"
	"github.com/netcracker/qubership-core-lib-go/v3/security"
	"github.com/netcracker/qubership-core-lib-go/v3/serviceloader"

	"github.com/netcracker/qubership-dbaas/test-apps/go-test-app-service/internal/postgresmigrations"
)

const defaultListenAddress = ":8080"
const dbPingTimeout = 5 * time.Second
const dbOperationTimeout = 30 * time.Second
const errorResponseMessage = "request failed"

func init() {
	serviceloader.Register(2, &security.DummyToken{})
}

type app struct {
	pgDatabase pgdbaas.Database
}

type postgresItem struct {
	ID        int64     `json:"id"`
	Name      string    `json:"name"`
	CreatedAt time.Time `json:"createdAt"`
}

type createPostgresItemRequest struct {
	Name string `json:"name"`
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	configloader.Init(configloader.EnvPropertySource())
	application := newApp(pgmodel.DbParams{Migrations: postgresmigrations.Migrations()})

	addr := os.Getenv("HTTP_ADDR")
	if addr == "" {
		addr = defaultListenAddress
	}

	server := &http.Server{
		Addr:              addr,
		Handler:           application.routes(),
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Printf("go-test-app-service listening on %s", addr)
	return server.ListenAndServe()
}

func newApp(pgParams pgmodel.DbParams) *app {
	pool := dbaasbase.NewDbaaSPool()
	pgClient := pgdbaas.NewClient(pool)
	return &app{pgDatabase: pgClient.ServiceDatabase(pgParams)}
}

func (a *app) routes() http.Handler {
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

func (a *app) handlePostgresPing(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, errors.New("method not allowed"))
		return
	}

	props, err := a.pgDatabase.FindConnectionProperties(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), dbPingTimeout)
	defer cancel()

	conn, err := pgx.Connect(ctx, props.Url)
	if err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Errorf("connect to postgres: %w", err))
		return
	}
	defer conn.Close(context.Background())

	var result int
	if err := conn.QueryRow(ctx, "SELECT 1").Scan(&result); err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Errorf("execute SELECT 1: %w", err))
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":   "ok",
		"url":      sanitizeURL(props.Url),
		"username": props.Username,
		"role":     props.Role,
		"result":   result,
	})
}

func (a *app) handlePostgresConnectionProperties(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, errors.New("method not allowed"))
		return
	}

	props, err := a.pgDatabase.FindConnectionProperties(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"url":      sanitizeURL(props.Url),
		"username": props.Username,
		"role":     props.Role,
		"roHost":   props.RoHost,
	})
}

func (a *app) handlePostgresItems(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		a.handleListPostgresItems(w, r)
	case http.MethodPost:
		a.handleCreatePostgresItem(w, r)
	case http.MethodDelete:
		a.handleDeletePostgresItems(w, r)
	default:
		writeError(w, http.StatusMethodNotAllowed, errors.New("method not allowed"))
	}
}

func (a *app) handleListPostgresItems(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), dbOperationTimeout)
	defer cancel()

	db, err := a.postgresSQLDB(ctx)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	rows, err := db.QueryContext(ctx, fmt.Sprintf("SELECT id, name, created_at FROM %s ORDER BY id", postgresmigrations.ItemsTable))
	if err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Errorf("list postgres items: %w", err))
		return
	}
	defer rows.Close()

	items := make([]postgresItem, 0)
	for rows.Next() {
		var item postgresItem
		if err := rows.Scan(&item.ID, &item.Name, &item.CreatedAt); err != nil {
			writeError(w, http.StatusInternalServerError, fmt.Errorf("scan postgres item: %w", err))
			return
		}
		items = append(items, item)
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Errorf("read postgres items: %w", err))
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{"items": items})
}

func (a *app) handleCreatePostgresItem(w http.ResponseWriter, r *http.Request) {
	var request createPostgresItemRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&request); err != nil {
		writeError(w, http.StatusBadRequest, fmt.Errorf("decode request: %w", err))
		return
	}

	name := strings.TrimSpace(request.Name)
	if name == "" {
		writeError(w, http.StatusBadRequest, errors.New("name is required"))
		return
	}
	if len(name) > 200 {
		writeError(w, http.StatusBadRequest, errors.New("name is too long"))
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), dbOperationTimeout)
	defer cancel()

	db, err := a.postgresSQLDB(ctx)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	var item postgresItem
	err = db.QueryRowContext(
		ctx,
		fmt.Sprintf("INSERT INTO %s (name) VALUES ($1) RETURNING id, name, created_at", postgresmigrations.ItemsTable),
		name,
	).Scan(&item.ID, &item.Name, &item.CreatedAt)
	if err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Errorf("create postgres item: %w", err))
		return
	}

	writeJSON(w, http.StatusCreated, map[string]interface{}{"item": item})
}

func (a *app) handleDeletePostgresItems(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), dbOperationTimeout)
	defer cancel()

	db, err := a.postgresSQLDB(ctx)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	result, err := db.ExecContext(ctx, fmt.Sprintf("DELETE FROM %s", postgresmigrations.ItemsTable))
	if err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Errorf("delete postgres items: %w", err))
		return
	}
	deleted, err := result.RowsAffected()
	if err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Errorf("count deleted postgres items: %w", err))
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{"deleted": deleted})
}

func (a *app) postgresSQLDB(ctx context.Context) (*sql.DB, error) {
	client, err := a.pgDatabase.GetPgClient()
	if err != nil {
		return nil, fmt.Errorf("create postgres client: %w", err)
	}
	db, err := client.GetSqlDb(ctx)
	if err != nil {
		return nil, fmt.Errorf("get postgres datasource: %w", err)
	}
	return db, nil
}

func sanitizeURL(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil {
		return "<invalid-url>"
	}
	if parsed.User == nil {
		return raw
	}
	username := parsed.User.Username()
	if username == "" {
		parsed.User = url.UserPassword("", "xxxxx")
	} else {
		parsed.User = url.UserPassword(username, "xxxxx")
	}
	return parsed.String()
}

func writeJSON(w http.ResponseWriter, status int, body interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		log.Printf("write response: %v", err)
	}
}

func writeError(w http.ResponseWriter, status int, err error) {
	log.Printf("request failed: %v", err)
	writeJSON(w, status, map[string]string{"error": errorResponseMessage})
}

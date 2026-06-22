package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/netcracker/qubership-dbaas/test-apps/go-test-app-service/internal/postgresmigrations"
)

const dbPingTimeout = 5 * time.Second
const dbOperationTimeout = 30 * time.Second

type postgresItem struct {
	ID        int64     `json:"id"`
	Name      string    `json:"name"`
	CreatedAt time.Time `json:"createdAt"`
}

type createPostgresItemRequest struct {
	Name string `json:"name"`
}

func (a *App) handlePostgresPing(w http.ResponseWriter, r *http.Request) {
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

func (a *App) handlePostgresConnectionProperties(w http.ResponseWriter, r *http.Request) {
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

func (a *App) handlePostgresItems(w http.ResponseWriter, r *http.Request) {
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

func (a *App) handleListPostgresItems(w http.ResponseWriter, r *http.Request) {
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

func (a *App) handleCreatePostgresItem(w http.ResponseWriter, r *http.Request) {
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024))
	var request createPostgresItemRequest
	if err := decoder.Decode(&request); err != nil {
		writeError(w, http.StatusBadRequest, fmt.Errorf("decode request: %w", err))
		return
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, errors.New("request body must contain a single JSON object"))
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

func (a *App) handleDeletePostgresItems(w http.ResponseWriter, r *http.Request) {
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

func (a *App) postgresSQLDB(ctx context.Context) (*sql.DB, error) {
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

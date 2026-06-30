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
	pgdbaas "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/baseproviders/tenant"

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

// withTenant pins the fixed tenant into the context for the tenant-scoped quadrants (Q3/Q4), so the
// tenant classifier resolves to a static {scope=tenant, tenantId=acme} that matches the mounted
// secret. It is a no-op for the service-scoped quadrants.
func withTenant(ctx context.Context, pinTenant bool) context.Context {
	if !pinTenant {
		return ctx
	}
	return context.WithValue(ctx, tenant.TenantContextName, tenant.NewTenantContextObject(fixedTenant))
}

func handlePostgresPing(db pgdbaas.Database, pinTenant bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			writeError(w, http.StatusMethodNotAllowed, errors.New("method not allowed"))
			return
		}

		props, err := db.FindConnectionProperties(withTenant(r.Context(), pinTenant))
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
}

func handlePostgresConnectionProperties(db pgdbaas.Database, pinTenant bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			writeError(w, http.StatusMethodNotAllowed, errors.New("method not allowed"))
			return
		}

		props, err := db.FindConnectionProperties(withTenant(r.Context(), pinTenant))
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
}

func handlePostgresItems(db pgdbaas.Database, pinTenant bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			listPostgresItems(w, r, db, pinTenant)
		case http.MethodPost:
			createPostgresItem(w, r, db, pinTenant)
		case http.MethodDelete:
			deletePostgresItems(w, r, db, pinTenant)
		default:
			writeError(w, http.StatusMethodNotAllowed, errors.New("method not allowed"))
		}
	}
}

func listPostgresItems(w http.ResponseWriter, r *http.Request, db pgdbaas.Database, pinTenant bool) {
	ctx, cancel := context.WithTimeout(withTenant(r.Context(), pinTenant), dbOperationTimeout)
	defer cancel()

	sqlDB, err := postgresSQLDB(ctx, db)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	rows, err := sqlDB.QueryContext(ctx, fmt.Sprintf("SELECT id, name, created_at FROM %s ORDER BY id", postgresmigrations.ItemsTable))
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

func createPostgresItem(w http.ResponseWriter, r *http.Request, db pgdbaas.Database, pinTenant bool) {
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

	ctx, cancel := context.WithTimeout(withTenant(r.Context(), pinTenant), dbOperationTimeout)
	defer cancel()

	sqlDB, err := postgresSQLDB(ctx, db)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	var item postgresItem
	err = sqlDB.QueryRowContext(
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

func deletePostgresItems(w http.ResponseWriter, r *http.Request, db pgdbaas.Database, pinTenant bool) {
	ctx, cancel := context.WithTimeout(withTenant(r.Context(), pinTenant), dbOperationTimeout)
	defer cancel()

	sqlDB, err := postgresSQLDB(ctx, db)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	result, err := sqlDB.ExecContext(ctx, fmt.Sprintf("DELETE FROM %s", postgresmigrations.ItemsTable))
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

func postgresSQLDB(ctx context.Context, db pgdbaas.Database) (*sql.DB, error) {
	client, err := db.GetPgClient()
	if err != nil {
		return nil, fmt.Errorf("create postgres client: %w", err)
	}
	sqlDB, err := client.GetSqlDb(ctx)
	if err != nil {
		return nil, fmt.Errorf("get postgres datasource: %w", err)
	}
	return sqlDB, nil
}

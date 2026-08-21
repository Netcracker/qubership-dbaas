package app

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	pgdbaas "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4"
	pgmodel "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4/model"
	"github.com/uptrace/bun"
)

type fakeDatabase struct {
	properties  *pgmodel.PgConnProperties
	findErr     error
	pgClient    pgdbaas.PgClient
	pgClientErr error
}

func (f *fakeDatabase) GetPgClient(...*pgmodel.PgOptions) (pgdbaas.PgClient, error) {
	if f.pgClientErr != nil {
		return nil, f.pgClientErr
	}
	return f.pgClient, nil
}

func (f *fakeDatabase) GetConnectionProperties(context.Context) (*pgmodel.PgConnProperties, error) {
	return f.properties, f.findErr
}

func (f *fakeDatabase) FindConnectionProperties(context.Context) (*pgmodel.PgConnProperties, error) {
	return f.properties, f.findErr
}

func TestHandlePostgresConnectionProperties(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name       string
		database   pgdbaas.Database
		wantStatus int
	}{
		{
			name: "success",
			database: &fakeDatabase{properties: &pgmodel.PgConnProperties{
				Url:      "postgres://user:secret@postgres:5432/appdb",
				Username: "user",
				Role:     "admin",
				RoHost:   "postgres-ro",
			}},
			wantStatus: http.StatusOK,
		},
		{
			name:       "resolution error",
			database:   &fakeDatabase{findErr: errors.New("resolution failed")},
			wantStatus: http.StatusInternalServerError,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodGet, "/postgres/connection-properties", nil)
			(&App{service: tc.database}).Handler().ServeHTTP(recorder, request)

			if tc.wantStatus != http.StatusOK {
				assertErrorResponse(t, recorder, tc.wantStatus)
				return
			}
			if recorder.Code != tc.wantStatus {
				t.Fatalf("status = %d, want %d", recorder.Code, tc.wantStatus)
			}

			var response map[string]string
			if err := json.NewDecoder(recorder.Body).Decode(&response); err != nil {
				t.Fatalf("decode response: %v", err)
			}
			if response["url"] != "postgres://user:xxxxx@postgres:5432/appdb" {
				t.Errorf("url = %q, want masked password", response["url"])
			}
			if response["username"] != "user" || response["role"] != "admin" || response["roHost"] != "postgres-ro" {
				t.Errorf("unexpected connection properties response: %#v", response)
			}
		})
	}
}

func TestPostgresReadEndpoints_RejectUnsupportedMethod(t *testing.T) {
	t.Parallel()

	for _, path := range []string{"/postgres/ping", "/postgres/connection-properties"} {
		path := path
		t.Run(path, func(t *testing.T) {
			t.Parallel()

			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodPost, path, nil)
			(&App{}).Handler().ServeHTTP(recorder, request)

			assertErrorResponse(t, recorder, http.StatusMethodNotAllowed)
		})
	}
}

func TestHandlePostgresItems_RejectsUnsupportedMethod(t *testing.T) {
	t.Parallel()

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPatch, "/postgres/items", nil)
	(&App{}).Handler().ServeHTTP(recorder, request)

	assertErrorResponse(t, recorder, http.StatusMethodNotAllowed)
}

func TestHandleCreatePostgresItem_RejectsInvalidInput(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name string
		body string
	}{
		{name: "invalid JSON", body: `{`},
		{name: "missing name", body: `{}`},
		{name: "blank name", body: `{"name":"   "}`},
		{name: "name too long", body: `{"name":"` + strings.Repeat("a", 201) + `"}`},
		{name: "trailing JSON", body: `{"name":"valid"}{"name":"other"}`},
		{name: "trailing garbage", body: `{"name":"valid"} garbage`},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodPost, "/postgres/items", strings.NewReader(tc.body))
			(&App{}).Handler().ServeHTTP(recorder, request)

			assertErrorResponse(t, recorder, http.StatusBadRequest)
		})
	}
}

// --- rotation probe ---

// stubConn is a database/sql driver connection that records the row the probe inserts and serves it
// back on the follow-up read. It implements the context-aware exec/query interfaces so database/sql
// never falls back to Prepare, which keeps the stub to the three statements the probe issues.
type stubConn struct {
	beginErr  error
	execErr   error
	queryErr  error
	commitErr error
	// storedName overrides what the read-back returns, so a silent write/read mismatch is testable.
	storedName *string
	inserted   string
}

func (c *stubConn) Prepare(string) (driver.Stmt, error) { return nil, errors.New("not implemented") }
func (c *stubConn) Close() error                        { return nil }
func (c *stubConn) Begin() (driver.Tx, error) {
	return c.BeginTx(context.Background(), driver.TxOptions{})
}

func (c *stubConn) BeginTx(context.Context, driver.TxOptions) (driver.Tx, error) {
	if c.beginErr != nil {
		return nil, c.beginErr
	}
	return &stubTx{conn: c}, nil
}

func (c *stubConn) ExecContext(_ context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	if c.execErr != nil {
		return nil, c.execErr
	}
	if strings.HasPrefix(query, "INSERT") && len(args) == 1 {
		c.inserted, _ = args[0].Value.(string)
	}
	return driver.RowsAffected(1), nil
}

func (c *stubConn) QueryContext(_ context.Context, _ string, _ []driver.NamedValue) (driver.Rows, error) {
	if c.queryErr != nil {
		return nil, c.queryErr
	}
	name := c.inserted
	if c.storedName != nil {
		name = *c.storedName
	}
	return &stubRows{name: name}, nil
}

type stubTx struct{ conn *stubConn }

func (t *stubTx) Commit() error   { return t.conn.commitErr }
func (t *stubTx) Rollback() error { return nil }

type stubRows struct {
	name string
	done bool
}

func (r *stubRows) Columns() []string { return []string{"name"} }
func (r *stubRows) Close() error      { return nil }
func (r *stubRows) Next(dest []driver.Value) error {
	if r.done {
		return io.EOF
	}
	r.done = true
	dest[0] = r.name
	return nil
}

type stubConnector struct {
	conn       *stubConn
	connectErr error
}

func (c *stubConnector) Connect(context.Context) (driver.Conn, error) {
	if c.connectErr != nil {
		return nil, c.connectErr
	}
	return c.conn, nil
}
func (c *stubConnector) Driver() driver.Driver { return nil }

// fakePgClient serves a datasource built on stubConn instead of a real PostgreSQL pool.
type fakePgClient struct {
	db  *sql.DB
	err error
}

func (f *fakePgClient) GetSqlDb(context.Context) (*sql.DB, error) {
	if f.err != nil {
		return nil, f.err
	}
	return f.db, nil
}

func (f *fakePgClient) GetBunDb(context.Context) (*bun.DB, error) {
	return nil, errors.New("not implemented")
}

func probeDatabase(conn *stubConn, connectErr error) *fakeDatabase {
	return &fakeDatabase{pgClient: &fakePgClient{
		db: sql.OpenDB(&stubConnector{conn: conn, connectErr: connectErr}),
	}}
}

func postRotationProbe(t *testing.T, db pgdbaas.Database, body string) *httptest.ResponseRecorder {
	t.Helper()
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/postgres-admin/rotation-probe", strings.NewReader(body))
	(&App{serviceAdmin: db}).Handler().ServeHTTP(recorder, request)
	return recorder
}

func TestHandleRotationProbe_Success(t *testing.T) {
	t.Parallel()

	conn := &stubConn{}
	recorder := postRotationProbe(t, probeDatabase(conn, nil), `{"probeId":"probe-42"}`)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d (body %s)", recorder.Code, http.StatusOK, recorder.Body.String())
	}

	var response map[string]string
	if err := json.NewDecoder(recorder.Body).Decode(&response); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if response["probeId"] != "probe-42" || response["status"] != "ok" {
		t.Errorf("unexpected probe response: %#v", response)
	}
	// The rotation test treats this body as safe to log, so it must never carry connection data.
	for _, forbidden := range []string{"url", "username", "password", "host", "role"} {
		if _, present := response[forbidden]; present {
			t.Errorf("probe response must not expose %q", forbidden)
		}
	}
	if conn.inserted != "probe-42" {
		t.Errorf("probe inserted %q, want probe-42", conn.inserted)
	}
}

func TestHandleRotationProbe_RejectsUnsupportedMethod(t *testing.T) {
	t.Parallel()

	for _, method := range []string{http.MethodGet, http.MethodDelete, http.MethodPatch} {
		t.Run(method, func(t *testing.T) {
			t.Parallel()

			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(method, "/postgres-admin/rotation-probe", nil)
			(&App{}).Handler().ServeHTTP(recorder, request)

			assertErrorResponse(t, recorder, http.StatusMethodNotAllowed)
		})
	}
}

func TestHandleRotationProbe_RejectsInvalidInput(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name string
		body string
	}{
		{name: "invalid JSON", body: `{`},
		{name: "empty body", body: ``},
		{name: "missing probeId", body: `{}`},
		{name: "blank probeId", body: `{"probeId":"   "}`},
		{name: "probeId too long", body: `{"probeId":"` + strings.Repeat("a", maxProbeIDLength+1) + `"}`},
		{name: "trailing JSON", body: `{"probeId":"a"}{"probeId":"b"}`},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			// A rejected request must fail before the datasource is touched; the nil database here
			// would panic if validation let it through.
			recorder := postRotationProbe(t, &fakeDatabase{}, tc.body)

			assertErrorResponse(t, recorder, http.StatusBadRequest)
		})
	}
}

func TestHandleRotationProbe_ReportsDatabaseFailures(t *testing.T) {
	t.Parallel()

	sqlFailure := errors.New("sql failed")
	mismatched := "someone-elses-row"

	tests := []struct {
		name     string
		database pgdbaas.Database
	}{
		{
			name:     "client acquisition failure",
			database: &fakeDatabase{pgClientErr: errors.New("no client")},
		},
		{
			name:     "datasource acquisition failure",
			database: &fakeDatabase{pgClient: &fakePgClient{err: errors.New("no datasource")}},
		},
		{
			name:     "connection failure",
			database: probeDatabase(&stubConn{}, errors.New("connection refused")),
		},
		{
			name:     "begin failure",
			database: probeDatabase(&stubConn{beginErr: sqlFailure}, nil),
		},
		{
			name:     "insert failure",
			database: probeDatabase(&stubConn{execErr: sqlFailure}, nil),
		},
		{
			name:     "read-back failure",
			database: probeDatabase(&stubConn{queryErr: sqlFailure}, nil),
		},
		{
			name:     "read-back mismatch",
			database: probeDatabase(&stubConn{storedName: &mismatched}, nil),
		},
		{
			name:     "commit failure",
			database: probeDatabase(&stubConn{commitErr: sqlFailure}, nil),
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			recorder := postRotationProbe(t, tc.database, `{"probeId":"probe-42"}`)

			assertErrorResponse(t, recorder, http.StatusInternalServerError)
		})
	}
}

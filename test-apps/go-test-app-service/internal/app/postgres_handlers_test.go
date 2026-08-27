package app

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	pgdbaas "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4"
	pgmodel "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4/model"
)

type fakeDatabase struct {
	properties *pgmodel.PgConnProperties
	findErr    error
}

func (f *fakeDatabase) GetPgClient(...*pgmodel.PgOptions) (pgdbaas.PgClient, error) {
	return nil, errors.New("not implemented")
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

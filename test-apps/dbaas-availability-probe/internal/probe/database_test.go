package probe

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

const testToken = "test-token-value"

func writeToken(t *testing.T, token string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "token")
	if err := os.WriteFile(path, []byte(token+"\n"), 0o600); err != nil {
		t.Fatalf("write token: %v", err)
	}
	return path
}

func testDatabaseConfig(t *testing.T, url string) DatabaseConfig {
	t.Helper()
	return DatabaseConfig{
		AggregatorURL:    url,
		TokenPath:        writeToken(t, testToken),
		Namespace:        "dbaas",
		MicroserviceName: "dbaas-transition-probe",
	}
}

const validDatabaseResponse = `{"name":"dbaas_probe_db","namespace":"dbaas","type":"postgresql",` +
	`"classifier":{"microserviceName":"dbaas-transition-probe","namespace":"dbaas","scope":"service"},` +
	`"connectionProperties":{"username":"probe-user","password":"s3cr3t-password","url":"jdbc:postgresql://pg/db"}}`

func databaseServer(t *testing.T, status int, body string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
}

func TestCheckDatabaseGet_SendsExpectedRequest(t *testing.T) {
	var gotMethod, gotPath, gotAuth string
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotPath, gotAuth = r.Method, r.URL.Path, r.Header.Get("Authorization")
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		_, _ = w.Write([]byte(validDatabaseResponse))
	}))
	defer srv.Close()

	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &strings.Builder{}, &sync.Mutex{}, AggregatorDatabaseGet,
		CheckDatabaseGet(client, testDatabaseConfig(t, srv.URL)))

	if !res.Success {
		t.Fatalf("expected success, got error=%q", res.Error)
	}
	if gotMethod != http.MethodPost || gotPath != "/api/v3/dbaas/dbaas/databases/get-by-classifier/postgresql" {
		t.Fatalf("unexpected request %s %s", gotMethod, gotPath)
	}
	if gotAuth != "Bearer "+testToken {
		t.Fatalf("expected the token from the file as a bearer token, got %q", gotAuth)
	}
	classifier, _ := gotBody["classifier"].(map[string]any)
	if classifier["microserviceName"] != "dbaas-transition-probe" || classifier["namespace"] != "dbaas" || classifier["scope"] != "service" {
		t.Fatalf("unexpected classifier %v", classifier)
	}
	if gotBody["originService"] != "dbaas-transition-probe" || gotBody["userRole"] != "admin" {
		t.Fatalf("unexpected request body %v", gotBody)
	}
}

func TestCheckDatabaseGet_ReadsTokenOnEveryRequest(t *testing.T) {
	var auths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auths = append(auths, r.Header.Get("Authorization"))
		_, _ = w.Write([]byte(validDatabaseResponse))
	}))
	defer srv.Close()

	cfg := testDatabaseConfig(t, srv.URL)
	check := CheckDatabaseGet(&http.Client{Timeout: time.Second}, cfg)
	if _, err := check(context.Background()); err != nil {
		t.Fatalf("first request: %v", err)
	}
	if err := os.WriteFile(cfg.TokenPath, []byte("rotated-token"), 0o600); err != nil {
		t.Fatalf("rotate token: %v", err)
	}
	if _, err := check(context.Background()); err != nil {
		t.Fatalf("second request: %v", err)
	}
	if len(auths) != 2 || auths[1] != "Bearer rotated-token" {
		t.Fatalf("expected the rotated token on the second request, got %v", auths)
	}
}

func TestCheckDatabaseGet_InvalidResponsesFail(t *testing.T) {
	cases := map[string]struct {
		status int
		body   string
	}{
		"not found":        {http.StatusNotFound, `{}`},
		"malformed JSON":   {http.StatusOK, `{not valid json`},
		"wrong namespace":  {http.StatusOK, strings.Replace(validDatabaseResponse, `"namespace":"dbaas","type"`, `"namespace":"other","type"`, 1)},
		"wrong type":       {http.StatusOK, strings.Replace(validDatabaseResponse, `"postgresql",`, `"mongodb",`, 1)},
		"empty name":       {http.StatusOK, strings.Replace(validDatabaseResponse, `"dbaas_probe_db"`, `""`, 1)},
		"wrong classifier": {http.StatusOK, strings.Replace(validDatabaseResponse, `"scope":"service"`, `"scope":"tenant"`, 1)},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			srv := databaseServer(t, tc.status, tc.body)
			defer srv.Close()

			client := &http.Client{Timeout: time.Second}
			res := runProbe(context.Background(), &strings.Builder{}, &sync.Mutex{}, AggregatorDatabaseGet,
				CheckDatabaseGet(client, testDatabaseConfig(t, srv.URL)))
			if res.Success {
				t.Fatalf("expected failure")
			}
			if res.HTTPCode != tc.status {
				t.Fatalf("expected httpCode %d, got %d", tc.status, res.HTTPCode)
			}
		})
	}
}

func TestCheckDatabaseGet_MissingTokenFails(t *testing.T) {
	srv := databaseServer(t, http.StatusOK, validDatabaseResponse)
	defer srv.Close()

	cfg := testDatabaseConfig(t, srv.URL)
	cfg.TokenPath = filepath.Join(t.TempDir(), "missing")
	res := runProbe(context.Background(), &strings.Builder{}, &sync.Mutex{}, AggregatorDatabaseGet,
		CheckDatabaseGet(&http.Client{Timeout: time.Second}, cfg))
	if res.Success || res.HTTPCode != 0 {
		t.Fatalf("expected a failure without an HTTP response, got success=%v httpCode=%d", res.Success, res.HTTPCode)
	}
}

func TestCheckDatabaseGet_NeverLogsSecrets(t *testing.T) {
	srv := databaseServer(t, http.StatusOK, strings.Replace(validDatabaseResponse, `"postgresql",`, `"mongodb",`, 1))
	defer srv.Close()

	var buf strings.Builder
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &sync.Mutex{}, AggregatorDatabaseGet,
		CheckDatabaseGet(client, testDatabaseConfig(t, srv.URL)))
	if res.Success {
		t.Fatalf("expected failure for the wrong type")
	}
	for _, secret := range []string{testToken, "s3cr3t-password", "probe-user", "jdbc:postgresql"} {
		if strings.Contains(buf.String(), secret) {
			t.Fatalf("probe output must not contain %q, got: %s", secret, buf.String())
		}
	}
}

func TestCreateDatabase_AcceptsCreatedAndExisting(t *testing.T) {
	for _, status := range []int{http.StatusCreated, http.StatusOK} {
		var gotMethod, gotPath string
		var gotBody map[string]any
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gotMethod, gotPath = r.Method, r.URL.Path
			_ = json.NewDecoder(r.Body).Decode(&gotBody)
			w.WriteHeader(status)
			_, _ = w.Write([]byte(validDatabaseResponse))
		}))

		got, err := CreateDatabase(context.Background(), &http.Client{Timeout: time.Second}, testDatabaseConfig(t, srv.URL))
		srv.Close()
		if err != nil || got != status {
			t.Fatalf("status %d: expected success, got status=%d err=%v", status, got, err)
		}
		if gotMethod != http.MethodPut || gotPath != "/api/v3/dbaas/dbaas/databases" {
			t.Fatalf("unexpected request %s %s", gotMethod, gotPath)
		}
		if gotBody["type"] != "postgresql" || gotBody["userRole"] != "admin" || gotBody["originService"] != "dbaas-transition-probe" {
			t.Fatalf("unexpected request body %v", gotBody)
		}
	}
}

func TestCreateDatabase_RejectsOtherStatuses(t *testing.T) {
	for _, status := range []int{http.StatusAccepted, http.StatusForbidden, http.StatusInternalServerError} {
		srv := databaseServer(t, status, `{"message":"s3cr3t-password"}`)
		got, err := CreateDatabase(context.Background(), &http.Client{Timeout: time.Second}, testDatabaseConfig(t, srv.URL))
		srv.Close()
		if err == nil || got != status {
			t.Fatalf("status %d: expected an error, got status=%d err=%v", status, got, err)
		}
		if strings.Contains(err.Error(), "s3cr3t-password") {
			t.Fatalf("error must not contain the response body: %v", err)
		}
	}
}

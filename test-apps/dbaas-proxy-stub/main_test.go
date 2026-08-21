package main

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestParseMappings(t *testing.T) {
	t.Parallel()

	t.Run("parses the shared format", func(t *testing.T) {
		t.Parallel()

		mappings, err := parseMappings("8801/postgresql@pg-patroni.postgres.svc.cluster.local:5432,8802/mongodb@mongos:27017")
		if err != nil {
			t.Fatalf("parseMappings: %v", err)
		}
		if len(mappings) != 2 {
			t.Fatalf("got %d mappings, want 2", len(mappings))
		}
		want := mapping{dbType: "postgresql", targetHost: "pg-patroni.postgres.svc.cluster.local", targetPort: 5432, listenPort: 8801}
		if mappings[0] != want {
			t.Errorf("mappings[0] = %+v, want %+v", mappings[0], want)
		}
	})

	t.Run("treats an empty value as no mappings", func(t *testing.T) {
		t.Parallel()

		mappings, err := parseMappings("  ")
		if err != nil || mappings != nil {
			t.Fatalf("parseMappings(blank) = %v, %v; want nil, nil", mappings, err)
		}
	})

	for _, invalid := range []string{
		"8801/postgresql",
		"8801@pg-patroni:5432",
		"8801/postgresql@pg-patroni",
		"port/postgresql@pg-patroni:5432",
		"8801/postgresql@pg-patroni:port",
		"8801/@pg-patroni:5432",
	} {
		t.Run("rejects "+invalid, func(t *testing.T) {
			t.Parallel()

			if _, err := parseMappings(invalid); err == nil {
				t.Errorf("parseMappings(%q) must fail", invalid)
			}
		})
	}
}

func TestNormalizeHost(t *testing.T) {
	t.Parallel()

	// The adapter and the mapping may spell the same Service differently; both must match.
	for _, host := range []string{
		"pg-patroni.postgres",
		"pg-patroni.postgres.svc",
		"pg-patroni.postgres.svc.cluster.local",
		"pg-patroni.postgres.svc.cluster.local.",
	} {
		if got := normalizeHost(host); got != "pg-patroni.postgres" {
			t.Errorf("normalizeHost(%q) = %q, want pg-patroni.postgres", host, got)
		}
	}
}

func newTestProxy(upstream string) *proxy {
	return &proxy{
		upstream:     upstream,
		tcpProxyHost: "tcp-proxy.dbaas",
		client:       http.DefaultClient,
		mappings: []mapping{{
			dbType:     "postgresql",
			targetHost: "pg-patroni.postgres",
			targetPort: 5432,
			listenPort: 8801,
		}},
	}
}

func TestHandleProxy_RewritesDatabaseEndpoint(t *testing.T) {
	t.Parallel()

	var receivedAuth, receivedPath string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedAuth = r.Header.Get("Authorization")
		receivedPath = r.URL.RequestURI()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{
			"type":"postgresql",
			"connectionProperties":{
				"host":"pg-patroni.postgres.svc.cluster.local",
				"port":5432,
				"url":"postgresql://pg-patroni.postgres.svc.cluster.local:5432/db_1",
				"username":"user_1"
			}
		}`)
	}))
	defer upstream.Close()

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPut, "/api/v3/dbaas/test-ns/databases", strings.NewReader("{}"))
	request.Header.Set("Authorization", "Basic dGVzdDp0ZXN0")
	newTestProxy(upstream.URL).handleProxy(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body %s)", recorder.Code, recorder.Body.String())
	}
	// The operator's own credentials have to survive the hop, or the aggregator rejects the call.
	if receivedAuth != "Basic dGVzdDp0ZXN0" {
		t.Errorf("upstream Authorization = %q, want the caller's header", receivedAuth)
	}
	if receivedPath != "/api/v3/dbaas/test-ns/databases" {
		t.Errorf("upstream path = %q, want the original request URI", receivedPath)
	}

	var response struct {
		ConnectionProperties struct {
			Host     string `json:"host"`
			Port     int    `json:"port"`
			URL      string `json:"url"`
			Username string `json:"username"`
		} `json:"connectionProperties"`
	}
	if err := json.NewDecoder(recorder.Body).Decode(&response); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if response.ConnectionProperties.Host != "tcp-proxy.dbaas" || response.ConnectionProperties.Port != 8801 {
		t.Errorf("endpoint = %s:%d, want tcp-proxy.dbaas:8801",
			response.ConnectionProperties.Host, response.ConnectionProperties.Port)
	}
	if response.ConnectionProperties.URL != "postgresql://tcp-proxy.dbaas:8801/db_1" {
		t.Errorf("url = %q, want the proxied host and port", response.ConnectionProperties.URL)
	}
	// Rewriting the endpoint must not disturb the rest of the connection properties.
	if response.ConnectionProperties.Username != "user_1" {
		t.Errorf("username = %q, want it preserved", response.ConnectionProperties.Username)
	}
}

func TestHandleProxy_LeavesUnmatchedResponsesAlone(t *testing.T) {
	t.Parallel()

	const body = `{"type":"postgresql","connectionProperties":{"host":"other-db.elsewhere","port":5432,"url":"postgresql://other-db.elsewhere:5432/db_1"}}`

	tests := []struct {
		name   string
		method string
		path   string
		status int
		body   string
	}{
		{name: "unmapped host", method: http.MethodPut, path: "/api/v3/dbaas/test-ns/databases", status: 200, body: body},
		{name: "unrelated path", method: http.MethodGet, path: "/api/v3/dbaas/test-ns/physical_databases", status: 200, body: body},
		{name: "error status", method: http.MethodPut, path: "/api/v3/dbaas/test-ns/databases", status: 500, body: body},
		{name: "non-JSON body", method: http.MethodPut, path: "/api/v3/dbaas/test-ns/databases", status: 200, body: "not json"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = io.WriteString(w, tc.body)
			}))
			defer upstream.Close()

			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(tc.method, tc.path, nil)
			newTestProxy(upstream.URL).handleProxy(recorder, request)

			if recorder.Code != tc.status {
				t.Errorf("status = %d, want %d", recorder.Code, tc.status)
			}
			if recorder.Body.String() != tc.body {
				t.Errorf("body was modified:\n got %s\nwant %s", recorder.Body.String(), tc.body)
			}
		})
	}
}

// fakeHAProxy accepts one admin command and replies with the configured output, so the management
// endpoints can be tested without a real HAProxy.
func fakeHAProxy(t *testing.T, reply string, received *string) string {
	t.Helper()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { _ = listener.Close() })

	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		buffer := make([]byte, 256)
		read, readErr := conn.Read(buffer)
		if readErr == nil && received != nil {
			*received = strings.TrimSpace(string(buffer[:read]))
		}
		_, _ = conn.Write([]byte(reply))
	}()
	return listener.Addr().String()
}

func TestHandleShutdown_TargetsTheMappedBackend(t *testing.T) {
	t.Parallel()

	var received string
	p := newTestProxy("")
	p.haproxyAddr = fakeHAProxy(t, "\n", &received)

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/haproxy/shutdown-sessions/postgresql", nil)
	request.SetPathValue("portOrDbType", "postgresql")
	p.handleShutdown(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body %s)", recorder.Code, recorder.Body.String())
	}
	if received != "shutdown sessions server be_8801/srv_8801" {
		t.Errorf("haproxy command = %q, want the mapped backend and server", received)
	}
}

func TestHandleShutdown_RejectsUnknownTarget(t *testing.T) {
	t.Parallel()

	for _, target := range []string{"mongodb", "9999"} {
		t.Run(target, func(t *testing.T) {
			t.Parallel()

			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodPost, "/haproxy/shutdown-sessions/"+target, nil)
			request.SetPathValue("portOrDbType", target)
			newTestProxy("").handleShutdown(recorder, request)

			if recorder.Code != http.StatusBadRequest {
				t.Errorf("status = %d, want 400", recorder.Code)
			}
		})
	}
}

func TestHandleCommand_ReturnsHAProxyOutput(t *testing.T) {
	t.Parallel()

	var received string
	p := newTestProxy("")
	p.haproxyAddr = fakeHAProxy(t, "0x1: proto=tcp fe=fe_8801 be=be_8801 srv=srv_8801\n", &received)

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/haproxy/cmd", strings.NewReader(`{"cmd":"show sess"}`))
	p.handleCommand(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body %s)", recorder.Code, recorder.Body.String())
	}
	if received != "show sess" {
		t.Errorf("haproxy command = %q, want 'show sess'", received)
	}

	// The body is a JSON string, which is what the rotation test parses.
	var output string
	if err := json.NewDecoder(recorder.Body).Decode(&output); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if !strings.Contains(output, "fe_8801") {
		t.Errorf("output = %q, want the raw haproxy session line", output)
	}
}

func TestHandleCommand_RejectsInvalidRequest(t *testing.T) {
	t.Parallel()

	for _, body := range []string{`{`, `{}`, `{"cmd":"   "}`} {
		t.Run(body, func(t *testing.T) {
			t.Parallel()

			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodPost, "/haproxy/cmd", strings.NewReader(body))
			newTestProxy("").handleCommand(recorder, request)

			if recorder.Code != http.StatusBadRequest {
				t.Errorf("status = %d, want 400", recorder.Code)
			}
		})
	}
}

func TestHandleFrontend_RejectsUnknownAction(t *testing.T) {
	t.Parallel()

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/haproxy/frontend/restart/postgresql", nil)
	request.SetPathValue("action", "restart")
	request.SetPathValue("portOrDbType", "postgresql")
	newTestProxy("").handleFrontend(recorder, request)

	if recorder.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", recorder.Code)
	}
}

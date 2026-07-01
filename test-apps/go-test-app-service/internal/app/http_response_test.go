package app

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSanitizeURL(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name string
		raw  string
		want string
	}{
		{
			name: "masks password",
			raw:  "postgres://user:secret@postgres:5432/appdb?sslmode=disable",
			want: "postgres://user:xxxxx@postgres:5432/appdb?sslmode=disable",
		},
		{
			name: "leaves URL without credentials unchanged",
			raw:  "postgres://postgres:5432/appdb",
			want: "postgres://postgres:5432/appdb",
		},
		{
			name: "masks password with empty username",
			raw:  "postgres://:secret@postgres:5432/appdb",
			want: "postgres://:xxxxx@postgres:5432/appdb",
		},
		{
			name: "handles invalid URL",
			raw:  "postgres://user:%zz@postgres/appdb",
			want: "<invalid-url>",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			if got := sanitizeURL(tc.raw); got != tc.want {
				t.Errorf("sanitizeURL(%q) = %q, want %q", tc.raw, got, tc.want)
			}
		})
	}
}

func TestWriteError_DoesNotExposeInternalError(t *testing.T) {
	t.Parallel()

	recorder := httptest.NewRecorder()
	writeError(recorder, http.StatusInternalServerError, errors.New("password=secret"))

	assertErrorResponse(t, recorder, http.StatusInternalServerError)
	if strings.Contains(recorder.Body.String(), "secret") {
		t.Fatalf("response leaked internal error: %s", recorder.Body.String())
	}
}

func assertErrorResponse(t *testing.T, recorder *httptest.ResponseRecorder, wantStatus int) {
	t.Helper()

	if recorder.Code != wantStatus {
		t.Fatalf("status = %d, want %d", recorder.Code, wantStatus)
	}
	if got := recorder.Header().Get("Content-Type"); got != "application/json" {
		t.Errorf("Content-Type = %q, want application/json", got)
	}
	if got := strings.TrimSpace(recorder.Body.String()); got != `{"error":"request failed"}` {
		t.Errorf("body = %q, want generic error response", got)
	}
}

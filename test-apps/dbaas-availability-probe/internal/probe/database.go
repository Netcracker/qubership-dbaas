package probe

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
)

const maxDecodeBytes = 1 << 20 // 1 MiB

// PostgresType is the database type the probe creates and retrieves.
const PostgresType = "postgresql"

// DatabaseConfig identifies the probe database and its aggregator authentication.
type DatabaseConfig struct {
	AggregatorURL    string
	TokenPath        string
	Namespace        string
	MicroserviceName string
}

type classifier struct {
	MicroserviceName string `json:"microserviceName"`
	Namespace        string `json:"namespace"`
	Scope            string `json:"scope"`
}

func (c DatabaseConfig) classifier() classifier {
	return classifier{MicroserviceName: c.MicroserviceName, Namespace: c.Namespace, Scope: "service"}
}

func (c DatabaseConfig) databasesURL() string {
	return c.AggregatorURL + "/api/v3/dbaas/" + c.Namespace + "/databases"
}

type getByClassifierRequest struct {
	Classifier    classifier `json:"classifier"`
	OriginService string     `json:"originService"`
	UserRole      string     `json:"userRole"`
}

type createDatabaseRequest struct {
	Classifier    classifier `json:"classifier"`
	Type          string     `json:"type"`
	OriginService string     `json:"originService"`
	UserRole      string     `json:"userRole"`
}

// databaseResponse omits connection properties so they cannot reach probe output.
type databaseResponse struct {
	Name       string     `json:"name"`
	Namespace  string     `json:"namespace"`
	Type       string     `json:"type"`
	Classifier classifier `json:"classifier"`
}

// readToken reads the projected token on every call so kubelet rotation is picked up.
func readToken(path string) (string, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("read service account token: %w", err)
	}
	token := strings.TrimSpace(string(raw))
	if token == "" {
		return "", fmt.Errorf("service account token file %s is empty", path)
	}
	return token, nil
}

// doJSON excludes tokens and response bodies from returned errors.
func doJSON(ctx context.Context, client *http.Client, cfg DatabaseConfig, method, url string, body any) (*http.Response, error) {
	token, err := readToken(cfg.TokenPath)
	if err != nil {
		return nil, err
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, method, url, bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	return client.Do(req)
}

func (r databaseResponse) validate(want classifier) error {
	switch {
	case r.Namespace != want.Namespace:
		return fmt.Errorf("unexpected database namespace %q", r.Namespace)
	case r.Type != PostgresType:
		return fmt.Errorf("unexpected database type %q", r.Type)
	case r.Name == "":
		return fmt.Errorf("database name is empty")
	case r.Classifier != want:
		return fmt.Errorf("unexpected classifier microserviceName=%q namespace=%q scope=%q",
			r.Classifier.MicroserviceName, r.Classifier.Namespace, r.Classifier.Scope)
	}
	return nil
}

// CheckDatabaseGet verifies retrieval of the probe database by classifier.
func CheckDatabaseGet(client *http.Client, cfg DatabaseConfig) CheckFunc {
	url := cfg.databasesURL() + "/get-by-classifier/" + PostgresType
	body := getByClassifierRequest{
		Classifier:    cfg.classifier(),
		OriginService: cfg.MicroserviceName,
		UserRole:      "admin",
	}
	return func(ctx context.Context) (int, error) {
		resp, err := doJSON(ctx, client, cfg, http.MethodPost, url, body)
		if err != nil {
			return 0, err
		}
		defer drainAndClose(resp)
		if resp.StatusCode != http.StatusOK {
			return resp.StatusCode, fmt.Errorf("unexpected status %d", resp.StatusCode)
		}
		var db databaseResponse
		if err := json.NewDecoder(io.LimitReader(resp.Body, maxDecodeBytes)).Decode(&db); err != nil {
			return resp.StatusCode, fmt.Errorf("decode database response: %w", err)
		}
		if err := db.validate(body.Classifier); err != nil {
			return resp.StatusCode, err
		}
		return resp.StatusCode, nil
	}
}

// CreateDatabase creates the probe database or accepts an existing one.
func CreateDatabase(ctx context.Context, client *http.Client, cfg DatabaseConfig) (int, error) {
	body := createDatabaseRequest{
		Classifier:    cfg.classifier(),
		Type:          PostgresType,
		OriginService: cfg.MicroserviceName,
		UserRole:      "admin",
	}
	resp, err := doJSON(ctx, client, cfg, http.MethodPut, cfg.databasesURL(), body)
	if err != nil {
		return 0, err
	}
	defer drainAndClose(resp)
	if resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusOK {
		return resp.StatusCode, fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	return resp.StatusCode, nil
}

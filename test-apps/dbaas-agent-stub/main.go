// Command dbaas-agent-stub is a minimal stand-in for the qubership dbaas-agent, used only by the
// sample-service integration tests to exercise the client libraries' REST-fallback path.
//
// On a mounted-secret miss the dbaas clients resolve their database through their configured
// dbaas-agent address. The real agent pulls in tenant-manager / internal-gateway / control-plane
// dependencies that the integration-test cluster does not have. For the fallback test we only need
// the two things the agent actually does to our get-or-create / get-by-classifier requests:
//
//  1. inject originService (= classifier.microserviceName) into the JSON body — dbaas-aggregator
//     requires it on Basic-auth calls (it otherwise fills it only from an M2M JWT principal), and
//     the Java/Go dbaas clients do not send it themselves;
//  2. authenticate to dbaas-aggregator with HTTP Basic as cluster-dba (the DB_CLIENT user).
//
// Everything else (tenant existence checks, namespace isolation, route registration) is
// deliberately omitted — this is a test fixture, not the production agent.
package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"time"
)

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func main() {
	aggregator := getenv("AGGREGATOR_URL", "http://dbaas-aggregator.dbaas:8080")
	user := getenv("DBAAS_USERNAME", "cluster-dba")
	pass := getenv("DBAAS_PASSWORD", "password")
	client := &http.Client{Timeout: 60 * time.Second}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK"))
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "read body: "+err.Error(), http.StatusInternalServerError)
			return
		}
		_ = r.Body.Close()
		body = injectOriginService(body)

		req, err := http.NewRequest(r.Method, aggregator+r.URL.RequestURI(), bytes.NewReader(body))
		if err != nil {
			http.Error(w, "build request: "+err.Error(), http.StatusInternalServerError)
			return
		}
		copyHeader(req.Header, r.Header, requestSkipHeaders)
		req.SetBasicAuth(user, pass)

		resp, err := client.Do(req)
		if err != nil {
			http.Error(w, "forward to aggregator: "+err.Error(), http.StatusBadGateway)
			return
		}
		defer resp.Body.Close()

		copyHeader(w.Header(), resp.Header, responseSkipHeaders)
		w.WriteHeader(resp.StatusCode)
		_, _ = io.Copy(w, resp.Body)
		log.Printf("%s %s -> %d", r.Method, r.URL.Path, resp.StatusCode)
	})

	addr := ":" + getenv("PORT", "8080")
	log.Printf("dbaas-agent-stub listening on %s, forwarding to %s as %s", addr, aggregator, user)
	log.Fatal(http.ListenAndServe(addr, mux))
}

// injectOriginService sets the top-level "originService" field to classifier.microserviceName when
// the body is a JSON object carrying such a classifier and originService is not already set. Bodies
// that are empty, not JSON objects, or lack a microserviceName classifier are returned unchanged.
func injectOriginService(body []byte) []byte {
	if len(body) == 0 {
		return body
	}
	var m map[string]any
	if err := json.Unmarshal(body, &m); err != nil {
		return body
	}
	if v, ok := m["originService"]; ok && v != "" {
		return body
	}
	classifier, ok := m["classifier"].(map[string]any)
	if !ok {
		return body
	}
	name, ok := classifier["microserviceName"].(string)
	if !ok || name == "" {
		return body
	}
	m["originService"] = name
	out, err := json.Marshal(m)
	if err != nil {
		return body
	}
	return out
}

// Authorization is replaced with the agent's cluster-dba Basic credentials; Host and the
// length/transfer framing headers are managed by the Go HTTP client/server from the actual body.
var requestSkipHeaders = map[string]bool{
	"Authorization":  true,
	"Host":           true,
	"Content-Length": true,
	"Connection":     true,
}

var responseSkipHeaders = map[string]bool{
	"Content-Length":    true,
	"Transfer-Encoding": true,
	"Connection":        true,
}

func copyHeader(dst, src http.Header, skip map[string]bool) {
	for key, values := range src {
		if skip[http.CanonicalHeaderKey(key)] {
			continue
		}
		for _, v := range values {
			dst.Add(key, v)
		}
	}
}

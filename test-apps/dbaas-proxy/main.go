// Command dbaas-proxy provides control-plane routing for the PostgreSQL credential-rotation
// integration test.
//
// The rotation test has to interrupt the application's database connection on demand. It can only
// do that if the application connects through a proxy it controls, which means the endpoint the
// operator writes into the generated Secret has to point at that proxy rather than at the database.
// This service performs the two jobs that make it possible:
//
//  1. reverse-proxy every /api request to dbaas-aggregator unchanged, so the operator reaches the
//     real control plane through it;
//  2. rewrite the host and port of matching connection properties in create/get database responses
//     to the TCP proxy, before the operator turns them into a Secret.
//
// It also exposes the HAProxy management endpoints the test drives, so a test can terminate live
// database sessions without holding an admin socket itself.
//
// The proxy rewrites database connection properties that carry a plain host, port, and URL. Other
// connection-property shapes pass through unchanged and produce a warning.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Only create and get-by-classifier responses carry the connection properties that the operator
// writes to a Secret. List and administration endpoints must pass through without rewrite attempts.
var createDatabasePathPattern = regexp.MustCompile(`^/api/v\d+/dbaas/[^/]+/databases/?$`)
var getDatabasePathPattern = regexp.MustCompile(
	`^/api/v\d+/dbaas/[^/]+/databases/get-by-classifier/[^/]+/?$`,
)

// hostSuffixPattern strips the parts of a cluster DNS name that do not change which Service it
// resolves to, so a mapping written as pg-patroni.postgres matches an adapter that returns
// pg-patroni.postgres.svc.cluster.local.
var hostSuffixPattern = regexp.MustCompile(`(\.svc)?(\.cluster\.local)?\.?$`)

// mapping is one proxied database: where the adapter says the database lives, and where the
// application should be told to connect instead.
type mapping struct {
	dbType     string
	targetHost string
	targetPort int
	listenPort int
}

type proxy struct {
	upstream     string
	haproxyAddr  string
	mappings     []mapping
	tcpProxyHost string
	client       *http.Client
}

func main() {
	p := &proxy{
		upstream:     getenv("DBAAS_PROXY_URL", "http://dbaas-aggregator:8080"),
		tcpProxyHost: getenv("TCP_PROXY_HOST", "tcp-proxy"),
		haproxyAddr:  strings.TrimPrefix(getenv("HAPROXY_ADMIN_URL", "tcp://tcp-proxy:9999"), "tcp://"),
		// A long timeout: database creation can block for as long as the adapter takes.
		client: &http.Client{Timeout: 6 * time.Minute},
	}

	var err error
	if p.mappings, err = parseMappings(os.Getenv("TCP_PROXY_MAPPINGS")); err != nil {
		log.Fatalf("Invalid TCP_PROXY_MAPPINGS: %v", err)
	}

	log.Printf("Upstream %s, HAProxy admin %s, TCP proxy host %s", p.upstream, p.haproxyAddr, p.tcpProxyHost)
	for _, m := range p.mappings {
		log.Printf("Mapping %s: %s:%d -> %s:%d", m.dbType, m.targetHost, m.targetPort, p.tcpProxyHost, m.listenPort)
	}
	if len(p.mappings) == 0 {
		log.Print("No mappings configured; every response is proxied without rewriting")
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("up"))
	})
	mux.HandleFunc("POST /haproxy/cmd", p.handleCommand)
	mux.HandleFunc("POST /haproxy/shutdown-sessions", p.handleShutdownAll)
	mux.HandleFunc("POST /haproxy/shutdown-sessions/{portOrDbType}", p.handleShutdown)
	mux.HandleFunc("POST /haproxy/frontend/{action}", p.handleFrontendAll)
	mux.HandleFunc("POST /haproxy/frontend/{action}/{portOrDbType}", p.handleFrontend)
	mux.HandleFunc("/api/", p.handleProxy)
	mux.HandleFunc("/api", p.handleProxy)

	log.Print("Listening on :8080")
	server := &http.Server{Addr: ":8080", Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	if err := server.ListenAndServe(); err != nil {
		log.Fatalf("Server stopped: %v", err)
	}
}

// handleProxy forwards the request to the aggregator and rewrites database endpoints on the way
// back. Headers are copied in both directions, so the caller's own credentials reach the aggregator.
func (p *proxy) handleProxy(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "read request body: "+err.Error(), http.StatusInternalServerError)
		return
	}
	_ = r.Body.Close()

	target := p.upstream + r.URL.RequestURI()
	req, err := http.NewRequestWithContext(r.Context(), r.Method, target, strings.NewReader(string(body)))
	if err != nil {
		http.Error(w, "build upstream request: "+err.Error(), http.StatusInternalServerError)
		return
	}
	copyHeader(req.Header, r.Header)

	response, err := p.client.Do(req)
	if err != nil {
		http.Error(w, "proxy to "+target+": "+err.Error(), http.StatusInternalServerError)
		return
	}
	defer response.Body.Close()

	responseBody, err := io.ReadAll(response.Body)
	if err != nil {
		http.Error(w, "read upstream response: "+err.Error(), http.StatusInternalServerError)
		return
	}

	if p.shouldRewrite(r.Method, r.URL.Path, response.StatusCode) {
		rewritten, changed, rewriteErr := p.rewriteDatabaseResponse(responseBody)
		if rewriteErr != nil {
			// Pass the original response through: a shape this proxy does not understand is not a
			// reason to fail a request the aggregator answered successfully.
			log.Printf("WARNING: Skipped database endpoint rewrite for response from %s %s (status %d): %v",
				r.Method, r.URL.Path, response.StatusCode, rewriteErr)
		} else {
			responseBody = rewritten
			if changed {
				log.Printf("Rewrote database endpoint in response to %s %s", r.Method, r.URL.Path)
			} else {
				log.Printf("WARNING: No database endpoint mapping applied to response from %s %s (status %d)",
					r.Method, r.URL.Path, response.StatusCode)
			}
		}
	}

	copyHeader(w.Header(), response.Header)
	w.Header().Del("Content-Length")
	w.WriteHeader(response.StatusCode)
	_, _ = w.Write(responseBody)
}

func (p *proxy) shouldRewrite(method, path string, status int) bool {
	if status != http.StatusOK && status != http.StatusCreated {
		return false
	}
	switch method {
	case http.MethodPut:
		return createDatabasePathPattern.MatchString(path)
	case http.MethodPost:
		return getDatabasePathPattern.MatchString(path)
	default:
		return false
	}
}

// rewriteDatabaseResponse replaces the database host and port in a create/get response with the TCP
// proxy address. It reports whether anything matched, and returns the body unchanged when the
// response does not carry a mapped database.
func (p *proxy) rewriteDatabaseResponse(body []byte) ([]byte, bool, error) {
	var response map[string]any
	if err := json.Unmarshal(body, &response); err != nil {
		return nil, false, fmt.Errorf("unmarshal database response: %w", err)
	}

	dbType, _ := response["type"].(string)
	properties, ok := response["connectionProperties"].(map[string]any)
	if !ok {
		return body, false, nil
	}

	host, hostOK := properties["host"].(string)
	portFloat, portOK := properties["port"].(float64)
	if !hostOK || !portOK {
		return body, false, nil
	}

	m, found := p.findMapping(dbType, host, int(portFloat))
	if !found {
		return body, false, nil
	}

	origin := host + ":" + strconv.Itoa(int(portFloat))
	replacement := p.tcpProxyHost + ":" + strconv.Itoa(m.listenPort)
	properties["host"] = p.tcpProxyHost
	properties["port"] = m.listenPort
	if url, isString := properties["url"].(string); isString {
		properties["url"] = strings.ReplaceAll(url, origin, replacement)
	}

	rewritten, err := json.Marshal(response)
	if err != nil {
		return nil, false, fmt.Errorf("marshal rewritten response: %w", err)
	}
	return rewritten, true, nil
}

func (p *proxy) findMapping(dbType, host string, port int) (mapping, bool) {
	normalized := normalizeHost(host)
	for _, m := range p.mappings {
		if m.dbType == dbType && m.targetPort == port && normalizeHost(m.targetHost) == normalized {
			return m, true
		}
	}
	return mapping{}, false
}

// --- HAProxy management ---

func (p *proxy) handleCommand(w http.ResponseWriter, r *http.Request) {
	var request struct {
		CMD string `json:"cmd"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&request); err != nil {
		http.Error(w, "decode command: "+err.Error(), http.StatusBadRequest)
		return
	}
	if strings.TrimSpace(request.CMD) == "" {
		http.Error(w, "cmd cannot be empty", http.StatusBadRequest)
		return
	}

	output, err := p.runCommand(request.CMD)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, output)
}

func (p *proxy) handleShutdownAll(w http.ResponseWriter, _ *http.Request) {
	for _, m := range p.mappings {
		if _, err := p.runCommand(shutdownCommand(m.listenPort)); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}
	writeJSON(w, http.StatusOK, "ok")
}

func (p *proxy) handleShutdown(w http.ResponseWriter, r *http.Request) {
	m, err := p.resolveMapping(r.PathValue("portOrDbType"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	output, err := p.runCommand(shutdownCommand(m.listenPort))
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, output)
}

func (p *proxy) handleFrontendAll(w http.ResponseWriter, r *http.Request) {
	action, err := frontendAction(r.PathValue("action"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	for _, m := range p.mappings {
		if _, cmdErr := p.runCommand(fmt.Sprintf("%s frontend fe_%d", action, m.listenPort)); cmdErr != nil {
			http.Error(w, cmdErr.Error(), http.StatusInternalServerError)
			return
		}
	}
	writeJSON(w, http.StatusOK, "ok")
}

func (p *proxy) handleFrontend(w http.ResponseWriter, r *http.Request) {
	action, err := frontendAction(r.PathValue("action"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	m, err := p.resolveMapping(r.PathValue("portOrDbType"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	output, err := p.runCommand(fmt.Sprintf("%s frontend fe_%d", action, m.listenPort))
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, output)
}

// runCommand sends one command to the HAProxy admin socket and returns its raw output. HAProxy
// answers a successful admin command with an empty line, so an empty result is a success.
func (p *proxy) runCommand(command string) (string, error) {
	log.Printf("Executing haproxy cmd: %s", command)
	conn, err := net.DialTimeout("tcp", p.haproxyAddr, 10*time.Second)
	if err != nil {
		return "", fmt.Errorf("dial haproxy admin socket %s: %w", p.haproxyAddr, err)
	}
	defer conn.Close()

	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
	if _, err := conn.Write([]byte(command + "\n")); err != nil {
		return "", fmt.Errorf("write haproxy command: %w", err)
	}
	output, err := io.ReadAll(conn)
	if err != nil {
		return "", fmt.Errorf("read haproxy response: %w", err)
	}
	if strings.HasPrefix(string(output), "Unknown command") {
		return "", fmt.Errorf("haproxy rejected command %q: %s", command, strings.TrimSpace(string(output)))
	}
	return string(output), nil
}

// resolveMapping accepts either a listen port or a database type.
func (p *proxy) resolveMapping(portOrDbType string) (mapping, error) {
	if port, err := strconv.Atoi(portOrDbType); err == nil {
		for _, m := range p.mappings {
			if m.listenPort == port {
				return m, nil
			}
		}
		return mapping{}, fmt.Errorf("no mapping for port %d", port)
	}
	for _, m := range p.mappings {
		if m.dbType == portOrDbType {
			return m, nil
		}
	}
	return mapping{}, fmt.Errorf("no mapping for database type %q", portOrDbType)
}

func shutdownCommand(listenPort int) string {
	return fmt.Sprintf("shutdown sessions server be_%d/srv_%d", listenPort, listenPort)
}

func frontendAction(action string) (string, error) {
	if action != "enable" && action != "disable" {
		return "", fmt.Errorf("invalid action %q, valid actions: enable, disable", action)
	}
	return action, nil
}

// --- configuration ---

// parseMappings reads the TCP_PROXY_MAPPINGS format:
//
//	<listenPort>/<dbType>@<targetHost>:<targetPort>[,...]
func parseMappings(raw string) ([]mapping, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, nil
	}

	var mappings []mapping
	for _, entry := range strings.Split(raw, ",") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		listenPart, targetPart, found := strings.Cut(entry, "@")
		if !found {
			return nil, fmt.Errorf("mapping %q must be <listenPort>/<dbType>@<host>:<port>", entry)
		}
		listenPort, dbType, found := strings.Cut(listenPart, "/")
		if !found {
			return nil, fmt.Errorf("mapping %q must name a database type as <listenPort>/<dbType>", entry)
		}
		targetHost, targetPort, found := strings.Cut(targetPart, ":")
		if !found {
			return nil, fmt.Errorf("mapping %q must give the target as <host>:<port>", entry)
		}

		parsedListen, err := strconv.Atoi(listenPort)
		if err != nil {
			return nil, fmt.Errorf("mapping %q has a non-numeric listen port: %w", entry, err)
		}
		parsedTarget, err := strconv.Atoi(targetPort)
		if err != nil {
			return nil, fmt.Errorf("mapping %q has a non-numeric target port: %w", entry, err)
		}
		if dbType == "" || targetHost == "" {
			return nil, fmt.Errorf("mapping %q must give both a database type and a target host", entry)
		}

		mappings = append(mappings, mapping{
			dbType:     dbType,
			targetHost: targetHost,
			targetPort: parsedTarget,
			listenPort: parsedListen,
		})
	}
	return mappings, nil
}

func normalizeHost(host string) string {
	return hostSuffixPattern.ReplaceAllString(strings.TrimSpace(host), "")
}

func getenv(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

// hopByHopHeaders are connection-scoped and must not be forwarded to the next hop.
var hopByHopHeaders = map[string]bool{
	"Connection":          true,
	"Keep-Alive":          true,
	"Proxy-Authenticate":  true,
	"Proxy-Authorization": true,
	"Te":                  true,
	"Trailer":             true,
	"Transfer-Encoding":   true,
	"Upgrade":             true,
}

func copyHeader(dst, src http.Header) {
	for name, values := range src {
		if hopByHopHeaders[http.CanonicalHeaderKey(name)] {
			continue
		}
		for _, value := range values {
			dst.Add(name, value)
		}
	}
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(payload); err != nil {
		log.Printf("Failed to write response: %v", err)
	}
}

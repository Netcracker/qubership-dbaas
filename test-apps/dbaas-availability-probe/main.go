package main

import (
	"context"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"
)

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func getenvMillis(key string, def time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	ms, err := strconv.Atoi(v)
	if err != nil || ms <= 0 {
		log.Printf("invalid %s=%q, using default %s", key, v, def)
		return def
	}
	return time.Duration(ms) * time.Millisecond
}

func getenvSeconds(key string, def time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	s, err := strconv.Atoi(v)
	if err != nil || s < 0 {
		log.Printf("invalid %s=%q, using default %s", key, v, def)
		return def
	}
	return time.Duration(s) * time.Second
}

// config is read once from the environment at startup — the probe never mutates it. Credentials are
// held only here and in the Authorization header built per request; they are never written to log
// output (see checkClassifier).
type config struct {
	aggregatorURL     string
	sampleServiceURL  string
	namespace         string
	dbType            string
	microserviceName  string
	scope             string
	dbaasUsername     string
	dbaasPassword     string
	probeInterval     time.Duration
	requestTimeout    time.Duration
	maxDurationSecond time.Duration // 0 = run until signaled
	mode              string
	itemMarker        string
}

func loadConfig() config {
	return config{
		aggregatorURL:     getenv("AGGREGATOR_URL", "http://dbaas-aggregator:8080"),
		sampleServiceURL:  getenv("SAMPLE_SERVICE_URL", "http://go-test-app-service:8080"),
		namespace:         getenv("NAMESPACE", "dbaas"),
		dbType:            getenv("DB_TYPE", "postgresql"),
		microserviceName:  getenv("MICROSERVICE_NAME", "go-test-app-service"),
		scope:             getenv("CLASSIFIER_SCOPE", "service"),
		dbaasUsername:     os.Getenv("DBAAS_USERNAME"),
		dbaasPassword:     os.Getenv("DBAAS_PASSWORD"),
		probeInterval:     getenvMillis("PROBE_INTERVAL_MS", time.Second),
		requestTimeout:    getenvMillis("PROBE_REQUEST_TIMEOUT_MS", 5000*time.Millisecond),
		maxDurationSecond: getenvSeconds("PROBE_MAX_DURATION_SECONDS", 0),
		mode:              getenv("PROBE_MODE", "probe"),
		itemMarker:        getenv("ITEM_MARKER", "dbaas-uptime-fixture"),
	}
}

func main() {
	cfg := loadConfig()

	switch cfg.mode {
	case "verify":
		runVerify(cfg, os.Stdout, os.Stderr)
	case "verify-post":
		runVerifyPost(cfg, os.Stdout, os.Stderr)
	case "verify-health":
		runVerifyHealth(cfg, os.Stdout, os.Stderr)
	default:
		runContinuousProbe(cfg, os.Stdout)
	}
}

// runContinuousProbe schedules the four probe kinds on independent tickers and never returns except on
// SIGINT/SIGTERM or (if set) PROBE_MAX_DURATION_SECONDS elapsing. A failed sample is recorded and
// probing continues — see probe.go's runProbe — so the final log captures the complete outage window
// instead of stopping at the first failure.
func runContinuousProbe(cfg config, out io.Writer) {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if cfg.maxDurationSecond > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, cfg.maxDurationSecond)
		defer cancel()
	}

	client := &http.Client{Timeout: cfg.requestTimeout}
	var mu sync.Mutex

	checks := map[string]checkFunc{
		"aggregator-ready":     checkReady(client, cfg.aggregatorURL),
		"aggregator-health":    checkHealth(client, cfg.aggregatorURL),
		"dbaas-classifier":     checkClassifier(client, cfg.aggregatorURL, cfg.namespace, cfg.dbType, cfg.microserviceName, cfg.scope, cfg.dbaasUsername, cfg.dbaasPassword),
		"sample-postgres-ping": checkSamplePing(client, cfg.sampleServiceURL),
	}

	var wg sync.WaitGroup
	for name, fn := range checks {
		wg.Add(1)
		go func(name string, fn checkFunc) {
			defer wg.Done()
			runLoop(ctx, out, &mu, name, fn, cfg.probeInterval, cfg.requestTimeout)
		}(name, fn)
	}
	wg.Wait()
	log.Printf("dbaas-availability-probe stopped")
}

// runLoop fires fn once per tick until ctx is done. Each call gets its own bounded sub-context so one
// hung request cannot delay the next tick indefinitely. A panic inside fn (there should never be one,
// but a probe process going silent mid-transition is worse than a logged failure) is recovered and
// recorded as a failed sample rather than crashing the whole probe.
func runLoop(ctx context.Context, out io.Writer, mu *sync.Mutex, name string, fn checkFunc, interval, timeout time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	runOnce := func() {
		defer func() {
			if r := recover(); r != nil {
				writeResult(out, mu, probeResult{
					Timestamp: time.Now().UTC().Format(timestampLayout),
					Probe:     name,
					Success:   false,
					Error:     "panic during probe execution",
				})
			}
		}()
		callCtx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()
		runProbe(callCtx, out, mu, name, fn)
	}

	runOnce()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			runOnce()
		}
	}
}

// Command dbaas-availability-probe checks aggregator availability during a Helm transition.
package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/netcracker/qubership-dbaas/test-apps/dbaas-availability-probe/internal/probe"
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
		fmt.Fprintf(os.Stderr, "invalid %s=%q, using default %s\n", key, v, def)
		return def
	}
	return time.Duration(ms) * time.Millisecond
}

func main() {
	if len(os.Args) > 1 && os.Args[1] == "evaluate" {
		runEvaluate(os.Args[2:])
		return
	}

	mode := getenv("PROBE_MODE", "probe")
	interval := getenvMillis("PROBE_INTERVAL_MS", time.Second)
	requestTimeout := getenvMillis("PROBE_REQUEST_TIMEOUT_MS", 5000*time.Millisecond)
	database := probe.DatabaseConfig{
		AggregatorURL:    getenv("API_DBAAS_ADDRESS", "http://dbaas-aggregator.dbaas:8080"),
		TokenPath:        tokenPath,
		Namespace:        probeNamespace,
		MicroserviceName: probeServiceName,
	}

	switch mode {
	case "verify-preflight":
		runVerifyPreflight(database, requestTimeout)
	default:
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		probe.RunContinuous(ctx, os.Stdout, probe.ContinuousConfig{
			Database:       database,
			Interval:       interval,
			RequestTimeout: requestTimeout,
		})
	}
}

const (
	tokenPath        = "/var/run/secrets/tokens/dbaas/token"
	probeNamespace   = "dbaas"
	probeServiceName = "dbaas-transition-probe"
)

func runVerifyPreflight(database probe.DatabaseConfig, requestTimeout time.Duration) {
	const pollInterval = time.Second
	const stableConsecutiveSeconds = 10
	const overallTimeout = 180 * time.Second

	client := &http.Client{Timeout: requestTimeout}
	health := probe.PreflightTarget{Name: probe.AggregatorHealth, Check: probe.CheckHealth(client, database.AggregatorURL)}
	databaseGet := probe.PreflightTarget{Name: probe.AggregatorDatabaseGet, Check: probe.CheckDatabaseGet(client, database)}

	if err := probe.RunPreflight(context.Background(), []probe.PreflightTarget{health}, requestTimeout, pollInterval, stableConsecutiveSeconds, overallTimeout); err != nil {
		fixtureError("aggregator health did not stabilize: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	status, err := probe.CreateDatabase(ctx, client, database)
	cancel()
	if err != nil {
		fixtureError("create the probe database: %v", err)
	}
	fmt.Fprintf(os.Stdout, "PROBE_DATABASE_READY status=%d\n", status)

	if err := probe.RunPreflight(context.Background(), []probe.PreflightTarget{health, databaseGet}, requestTimeout, pollInterval, stableConsecutiveSeconds, overallTimeout); err != nil {
		fixtureError("pre-transition checks did not stabilize: %v", err)
	}
	fmt.Fprintln(os.Stdout, "PRE_TRANSITION_CHECKS_STABLE")
}

func fixtureError(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "FIXTURE_ERROR: "+format+"\n", args...)
	os.Exit(1)
}

// runEvaluate exits with an error when the captured probe log violates the availability contract.
func runEvaluate(args []string) {
	fs := flag.NewFlagSet("evaluate", flag.ExitOnError)
	logsPath := fs.String("logs", "", "path to the captured probe JSONL log (current+previous container logs concatenated)")
	probeStart := fs.String("probe-start", "", "measurement start timestamp, "+probe.TimestampLayout+" layout")
	transitionStart := fs.String("transition-start", "", "Helm upgrade start timestamp")
	transitionEnd := fs.String("transition-end", "", "Helm upgrade end timestamp")
	measurementEnd := fs.String("measurement-end", "", "measurement end timestamp")
	restarted := fs.Bool("restarted", false, "whether the probe container's restart count was > 0")
	maxGap := fs.Duration("max-gap", 3*time.Second, "maximum permitted gap between samples")
	interval := fs.Duration("interval", time.Second, "expected probe interval")
	minSamples := fs.Int("min-samples", 3, "minimum samples required per probe kind in the baseline and post-transition windows")
	fs.Parse(args)

	if *logsPath == "" || *probeStart == "" || *transitionStart == "" || *transitionEnd == "" || *measurementEnd == "" {
		fmt.Fprintln(os.Stderr, "FIXTURE_ERROR: -logs, -probe-start, -transition-start, -transition-end, and -measurement-end are all required")
		os.Exit(2)
	}

	logs, err := os.ReadFile(*logsPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "FIXTURE_ERROR: read %s: %v\n", *logsPath, err)
		os.Exit(2)
	}

	parseTimestamp := func(flagName, value string) time.Time {
		t, err := time.Parse(probe.TimestampLayout, value)
		if err != nil {
			fmt.Fprintf(os.Stderr, "FIXTURE_ERROR: -%s=%q: %v\n", flagName, value, err)
			os.Exit(2)
		}
		return t
	}

	records, parseErrors := probe.ParseRecords(logs)
	result := probe.Evaluate(records, probe.EvalParams{
		ProbeStart:          parseTimestamp("probe-start", *probeStart),
		TransitionStart:     parseTimestamp("transition-start", *transitionStart),
		TransitionEnd:       parseTimestamp("transition-end", *transitionEnd),
		MeasurementEnd:      parseTimestamp("measurement-end", *measurementEnd),
		ExpectedProbes:      probe.ExpectedKinds(),
		Interval:            *interval,
		MaxGap:              *maxGap,
		ContainerRestarted:  *restarted,
		MinSamplesPerWindow: *minSamples,
		ParseErrors:         parseErrors,
	})

	fmt.Println(result.Summary())
	if !result.Passed() {
		os.Exit(1)
	}
}

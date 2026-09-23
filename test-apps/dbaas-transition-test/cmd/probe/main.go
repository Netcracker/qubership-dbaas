// Command dbaas-transition-probe continuously exercises dbaas-aggregator from inside the target
// Kind cluster while its Helm release is upgraded or downgraded, so an availability regression
// during the transition shows up as a recorded probe failure instead of a silent gap. It talks to
// in-cluster Service DNS only — never through kubectl port-forward, whose own interruptions would
// otherwise look indistinguishable from a real DBaaS outage.
//
// In PROBE_MODE=verify-preflight it instead runs the pre-transition prerequisite: both pinned
// aggregator pod URLs and the sample-service path must report healthy for ten consecutive
// one-second checks before the harness starts the measured baseline.
//
// Run as `dbaas-availability-probe evaluate ...` (a discrete subcommand, not a PROBE_MODE — this
// one runs once on the test runner host, not continuously in a Pod) it instead judges a captured
// probe log against the availability contract: see runEvaluate. This is the only Go logic that
// runs outside the cluster; every Helm/kubectl/Kind/secret-generation step lives in the KUTTL test
// steps and the workflow that invokes them, not in this binary.
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

	"github.com/netcracker/qubership-dbaas/test-apps/dbaas-transition-test/internal/probe"
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
	aggregatorURL := getenv("AGGREGATOR_URL", "http://dbaas-aggregator:8080")
	sampleServiceURL := getenv("SAMPLE_SERVICE_URL", "http://go-test-app-service:8080")
	interval := getenvMillis("PROBE_INTERVAL_MS", time.Second)
	requestTimeout := getenvMillis("PROBE_REQUEST_TIMEOUT_MS", 5000*time.Millisecond)

	switch mode {
	case "verify-preflight":
		runVerifyPreflight(sampleServiceURL, requestTimeout)
	default:
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		probe.RunContinuous(ctx, os.Stdout, probe.ContinuousConfig{
			AggregatorURL:    aggregatorURL,
			SampleServiceURL: sampleServiceURL,
			Interval:         interval,
			RequestTimeout:   requestTimeout,
		})
	}
}

// runVerifyPreflight requires sustained health from both pinned aggregator pods and the
// sample-service path before returning. Pod 1/2 name and URL come from
// AGGREGATOR_POD_{1,2}_{NAME,URL}, set by the harness for each run.
func runVerifyPreflight(sampleServiceURL string, requestTimeout time.Duration) {
	pod1Name, pod1URL := os.Getenv("AGGREGATOR_POD_1_NAME"), os.Getenv("AGGREGATOR_POD_1_URL")
	pod2Name, pod2URL := os.Getenv("AGGREGATOR_POD_2_NAME"), os.Getenv("AGGREGATOR_POD_2_URL")
	if pod1Name == "" || pod1URL == "" || pod2Name == "" || pod2URL == "" {
		fmt.Fprintln(os.Stderr, "FIXTURE_ERROR: AGGREGATOR_POD_{1,2}_{NAME,URL} must all be set")
		os.Exit(1)
	}

	client := &http.Client{Timeout: requestTimeout}
	targets := []probe.PreflightTarget{
		{Name: pod1Name, Check: probe.CheckHealth(client, pod1URL)},
		{Name: pod2Name, Check: probe.CheckHealth(client, pod2URL)},
		{Name: "sample-service", Check: probe.CheckSamplePing(client, sampleServiceURL)},
	}

	const pollInterval = time.Second
	const stableConsecutiveSeconds = 10
	const overallTimeout = 180 * time.Second

	if err := probe.RunPreflight(context.Background(), targets, requestTimeout, pollInterval, stableConsecutiveSeconds, overallTimeout); err != nil {
		fmt.Fprintf(os.Stderr, "FIXTURE_ERROR: pre-transition checks did not stabilize: %v\n", err)
		os.Exit(1)
	}
	fmt.Fprintln(os.Stdout, "PRE_TRANSITION_CHECKS_STABLE")
}

// runEvaluate judges a captured continuous-probe log against the availability contract: zero
// recorded failures, every expected probe kind present with enough baseline/post samples, no gap
// beyond -max-gap, no probe container restart, and no unparseable log line. It prints a textual
// summary and exits 1 if the contract was violated — the KUTTL evaluate step's own exit code.
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

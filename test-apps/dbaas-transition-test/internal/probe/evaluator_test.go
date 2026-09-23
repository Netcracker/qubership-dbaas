package probe

import (
	"strings"
	"testing"
	"time"
)

var (
	evalProbeStart      = time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	evalTransitionStart = evalProbeStart.Add(40 * time.Second)
	evalTransitionEnd   = evalTransitionStart.Add(10 * time.Second)
	evalMeasurementEnd  = evalTransitionEnd.Add(65 * time.Second)
)

func baseParams() EvalParams {
	return EvalParams{
		ProbeStart:          evalProbeStart,
		TransitionStart:     evalTransitionStart,
		TransitionEnd:       evalTransitionEnd,
		MeasurementEnd:      evalMeasurementEnd,
		ExpectedProbes:      ExpectedKinds(),
		Interval:            time.Second,
		MaxGap:              3 * time.Second,
		MinSamplesPerWindow: 3,
	}
}

// everySecond returns one successful record per kind, once per second, from start (inclusive) to
// end (inclusive).
func everySecond(kinds []string, start, end time.Time) []Record {
	var out []Record
	for t := start; !t.After(end); t = t.Add(time.Second) {
		for _, kind := range kinds {
			out = append(out, Record{
				Timestamp: t.Format(TimestampLayout),
				Probe:     kind,
				Success:   true,
				HTTPCode:  200,
			})
		}
	}
	return out
}

func cleanRun() []Record {
	return everySecond(ExpectedKinds(), evalProbeStart, evalMeasurementEnd)
}

func TestEvaluate_CompletelySuccessfulRunPasses(t *testing.T) {
	result := Evaluate(cleanRun(), baseParams())
	if !result.Passed() {
		t.Fatalf("expected a clean run to pass, got failures: %v", result.FailureReasons())
	}
}

func TestEvaluate_FailedReadinessSampleFails(t *testing.T) {
	records := cleanRun()
	records = append(records, Record{
		Timestamp: evalTransitionStart.Add(2 * time.Second).Format(TimestampLayout),
		Probe:     AggregatorReady,
		Success:   false,
		HTTPCode:  503,
		Error:     "unexpected status 503",
	})

	result := Evaluate(records, baseParams())
	if result.Passed() {
		t.Fatalf("expected a failed readiness sample to fail the run")
	}
	assertReasonContains(t, result, "aggregator-ready")
}

func TestEvaluate_HealthProblemFails(t *testing.T) {
	records := cleanRun()
	records = append(records, Record{
		Timestamp: evalTransitionStart.Add(2 * time.Second).Format(TimestampLayout),
		Probe:     AggregatorHealth,
		Success:   false,
		HTTPCode:  200,
		Error:     "health status=PROBLEM components=adaptersAccessIndicator:PROBLEM",
	})

	result := Evaluate(records, baseParams())
	if result.Passed() {
		t.Fatalf("expected a PROBLEM health sample to fail the run")
	}
	assertReasonContains(t, result, "aggregator-health")
}

func TestEvaluate_FailedPostgresPingFails(t *testing.T) {
	records := cleanRun()
	records = append(records, Record{
		Timestamp: evalTransitionStart.Add(2 * time.Second).Format(TimestampLayout),
		Probe:     SamplePostgresPing,
		Success:   false,
		HTTPCode:  200,
		Error:     "unexpected ping payload status=ok result=0",
	})

	result := Evaluate(records, baseParams())
	if result.Passed() {
		t.Fatalf("expected a failed ping sample to fail the run")
	}
	assertReasonContains(t, result, "sample-postgres-ping")
}

func TestEvaluate_MissingProbeKindFails(t *testing.T) {
	records := everySecond([]string{AggregatorReady, AggregatorHealth}, evalProbeStart, evalMeasurementEnd)

	result := Evaluate(records, baseParams())
	if result.Passed() {
		t.Fatalf("expected a run missing sample-postgres-ping entirely to fail")
	}
	if len(result.MissingProbes) != 1 || result.MissingProbes[0] != SamplePostgresPing {
		t.Fatalf("expected sample-postgres-ping to be reported missing, got %v", result.MissingProbes)
	}
}

func TestEvaluate_GapGreaterThanLimitFails(t *testing.T) {
	records := cleanRun()
	// Remove every sample of one kind in a 5-second stretch inside the baseline window.
	gapStart := evalProbeStart.Add(10 * time.Second)
	gapEnd := gapStart.Add(5 * time.Second)
	var filtered []Record
	for _, rec := range records {
		ts, _ := time.Parse(TimestampLayout, rec.Timestamp)
		if rec.Probe == AggregatorReady && !ts.Before(gapStart) && ts.Before(gapEnd) {
			continue
		}
		filtered = append(filtered, rec)
	}

	result := Evaluate(filtered, baseParams())
	if result.Passed() {
		t.Fatalf("expected a >3s gap to fail the run")
	}
	assertReasonContains(t, result, "gap")
}

func TestEvaluate_StartBoundaryGapFails(t *testing.T) {
	// The first aggregator-ready sample doesn't appear until 5s after probeStart.
	records := cleanRun()
	var filtered []Record
	for _, rec := range records {
		ts, _ := time.Parse(TimestampLayout, rec.Timestamp)
		if rec.Probe == AggregatorReady && ts.Before(evalProbeStart.Add(5*time.Second)) {
			continue
		}
		filtered = append(filtered, rec)
	}

	result := Evaluate(filtered, baseParams())
	if result.Passed() {
		t.Fatalf("expected a start-boundary gap (probe starts late) to fail the run")
	}
}

func TestEvaluate_EndBoundaryGapFails(t *testing.T) {
	// The last aggregator-ready sample is 5s before measurementEnd.
	records := cleanRun()
	var filtered []Record
	for _, rec := range records {
		ts, _ := time.Parse(TimestampLayout, rec.Timestamp)
		if rec.Probe == AggregatorReady && ts.After(evalMeasurementEnd.Add(-5*time.Second)) {
			continue
		}
		filtered = append(filtered, rec)
	}

	result := Evaluate(filtered, baseParams())
	if result.Passed() {
		t.Fatalf("expected an end-boundary gap (probe stops early) to fail the run")
	}
}

func TestEvaluate_EmptyTransitionWindowFromFastRolloutPasses(t *testing.T) {
	// transitionStart == transitionEnd: a Helm upgrade that completed in under a second between two
	// probe samples. No sample-count floor applies to the transition window.
	params := baseParams()
	params.TransitionEnd = params.TransitionStart

	records := everySecond(ExpectedKinds(), evalProbeStart, evalMeasurementEnd)
	result := Evaluate(records, params)
	if !result.Passed() {
		t.Fatalf("expected an empty transition window to be acceptable, got failures: %v", result.FailureReasons())
	}
}

func TestEvaluate_ContainerRestartFails(t *testing.T) {
	params := baseParams()
	params.ContainerRestarted = true

	result := Evaluate(cleanRun(), params)
	if result.Passed() {
		t.Fatalf("expected a probe container restart to fail the run regardless of sample data")
	}
	assertReasonContains(t, result, "restarted")
}

func TestEvaluate_RecordsOutsideMeasuredWindowDoNotCountTowardSamplesOrGap(t *testing.T) {
	records := cleanRun()
	// Add a burst of samples well after measurementEnd, as if log collection were delayed. These
	// must not paper over a real gap inside the window or
	// inflate the post-window sample count.
	for t := evalMeasurementEnd.Add(time.Minute); t.Before(evalMeasurementEnd.Add(2 * time.Minute)); t = t.Add(time.Second) {
		records = append(records, Record{
			Timestamp: t.Format(TimestampLayout),
			Probe:     AggregatorReady,
			Success:   true,
			HTTPCode:  200,
		})
	}

	result := Evaluate(records, baseParams())
	if !result.Passed() {
		t.Fatalf("expected out-of-window records to be ignored, got failures: %v", result.FailureReasons())
	}
}

func TestParseRecords_MalformedJSONIsReportedNotDropped(t *testing.T) {
	logs := `{"timestamp":"2026-01-01T00:00:00.000000000Z","probe":"aggregator-ready","success":true,"httpCode":200}
not valid json
{"timestamp":"2026-01-01T00:00:01.000000000Z","probe":"aggregator-ready","success":true,"httpCode":200}
`
	records, parseErrors := ParseRecords([]byte(logs))
	if len(records) != 2 {
		t.Fatalf("expected 2 valid records, got %d", len(records))
	}
	if len(parseErrors) != 1 {
		t.Fatalf("expected 1 parse error, got %d: %v", len(parseErrors), parseErrors)
	}

	params := baseParams()
	params.ParseErrors = parseErrors
	result := Evaluate(records, params)
	if result.Passed() {
		t.Fatalf("expected a run with a malformed log line to fail")
	}
	assertReasonContains(t, result, "failed to parse")
}

func TestParseRecords_InvalidTimestampIsReportedNotDropped(t *testing.T) {
	logs := `{"timestamp":"not-a-timestamp","probe":"aggregator-ready","success":true,"httpCode":200}`
	records, parseErrors := ParseRecords([]byte(logs))
	if len(records) != 0 {
		t.Fatalf("expected the invalid record to be rejected, got %d records", len(records))
	}
	if len(parseErrors) != 1 || !strings.Contains(parseErrors[0], "invalid timestamp") {
		t.Fatalf("expected one invalid-timestamp error, got %v", parseErrors)
	}
}

func TestResult_Summary_PassReportsPerProbeCounts(t *testing.T) {
	result := Evaluate(cleanRun(), baseParams())
	summary := result.Summary()
	if !strings.HasPrefix(summary, "PASS:") {
		t.Fatalf("expected a PASS summary, got: %s", summary)
	}
	for _, kind := range ExpectedKinds() {
		if !strings.Contains(summary, "probe="+kind) {
			t.Fatalf("expected the summary to mention probe=%s, got: %s", kind, summary)
		}
	}
}

func TestResult_Summary_FailReportsEveryReason(t *testing.T) {
	records := everySecond([]string{AggregatorReady, AggregatorHealth}, evalProbeStart, evalMeasurementEnd)
	result := Evaluate(records, baseParams())
	summary := result.Summary()
	if !strings.HasPrefix(summary, "FAIL:") {
		t.Fatalf("expected a FAIL summary, got: %s", summary)
	}
	if !strings.Contains(summary, SamplePostgresPing) {
		t.Fatalf("expected the summary to name the missing probe kind, got: %s", summary)
	}
}

func assertReasonContains(t *testing.T, result Result, substr string) {
	t.Helper()
	for _, reason := range result.FailureReasons() {
		if strings.Contains(reason, substr) {
			return
		}
	}
	t.Fatalf("expected a failure reason containing %q, got: %v", substr, result.FailureReasons())
}

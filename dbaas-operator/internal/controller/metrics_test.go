package controller

import (
	"errors"
	"fmt"
	"testing"

	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

func TestAggregatorResultClassifiesOwnershipBuckets(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want string
	}{
		{name: "success", err: nil, want: resultSuccess},
		{name: "auth", err: &aggregatorclient.AggregatorError{StatusCode: 401}, want: resultAuthError},
		{name: "spec rejection", err: &aggregatorclient.AggregatorError{StatusCode: 409}, want: resultSpecRejection},
		{name: "server", err: &aggregatorclient.AggregatorError{StatusCode: 500}, want: resultServerError},
		{name: "wrapped server", err: fmt.Errorf("wrapped: %w", &aggregatorclient.AggregatorError{StatusCode: 503}), want: resultServerError},
		{name: "network", err: errors.New("dial tcp: connection refused"), want: resultNetworkError},
		{name: "wrapped network", err: fmt.Errorf("connect: %w", errors.New("dial timeout")), want: resultNetworkError},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := aggregatorResult(tt.err); got != tt.want {
				t.Fatalf("aggregatorResult() = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestSecretResolutionReason(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want string
	}{
		{name: "typed", err: &secretResolutionError{reason: secretReasonKeyMissing, err: errors.New("missing")}, want: secretReasonKeyMissing},
		{name: "wrapped typed", err: fmt.Errorf("wrapped: %w", &secretResolutionError{reason: secretReasonForbidden, err: errors.New("forbidden")}), want: secretReasonForbidden},
		{name: "generic", err: errors.New("other"), want: secretReasonReadFailed},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := secretResolutionReason(tt.err); got != tt.want {
				t.Fatalf("secretResolutionReason() = %q, want %q", got, tt.want)
			}
		})
	}
}

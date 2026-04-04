/*
Copyright 2026.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package controller

// Event reason constants shared across all controllers.
//
// Each constant is used both as a Kubernetes Event reason (what appears in
// `kubectl describe` and `kubectl get events`) and as a Condition reason in
// the CR status. Keeping them in one place prevents the two from drifting.
//
// Naming conventions (https://github.com/kubernetes/community/blob/master/contributors/devel/sig-architecture/api-conventions.md#events):
//   - CamelCase, no spaces
//   - Tense: past for one-off successes ("DatabaseRegistered", "PolicyApplied"), present
//     participle for ongoing problems that repeat on each reconcile ("Unauthorized")
const (
	// EventReasonDatabaseRegistered is emitted when an ExternalDatabase is
	// successfully registered with dbaas-aggregator. Type: Normal.
	EventReasonDatabaseRegistered = "DatabaseRegistered"

	// EventReasonPolicyApplied is emitted when a DbPolicy is successfully
	// applied via dbaas-aggregator POST /api/declarations/v1/apply. Type: Normal.
	EventReasonPolicyApplied = "PolicyApplied"

	// EventReasonProvisioningStarted is emitted when dbaas-aggregator returns HTTP 202
	// for a DatabaseDeclaration, meaning the async operation has been accepted.
	// Type: Normal (informational — not yet complete).
	EventReasonProvisioningStarted = "ProvisioningStarted"

	// EventReasonDatabaseProvisioned is emitted when a DatabaseDeclaration is
	// successfully provisioned by dbaas-aggregator (either via HTTP 200 sync or
	// after polling completes with COMPLETED). Type: Normal.
	EventReasonDatabaseProvisioned = "DatabaseProvisioned"

	// EventReasonInvalidSpec is emitted when the CR spec fails pre-flight
	// validation before the aggregator is even contacted. Type: Warning.
	EventReasonInvalidSpec = "InvalidSpec"

	// EventReasonSecretError is emitted when a Secret referenced by
	// ConnectionProperties.credentialsSecretRef cannot be read. Type: Warning.
	EventReasonSecretError = "SecretError"

	// EventReasonUnauthorized is emitted when dbaas-aggregator returns HTTP 401.
	// Indicates the operator's credentials or role binding are misconfigured;
	// the CR spec is not at fault. Type: Warning.
	EventReasonUnauthorized = "Unauthorized"

	// EventReasonAggregatorRejected is emitted when dbaas-aggregator returns a
	// 4xx error other than 401 (e.g. 400, 403, 409). Indicates a permanent
	// spec error — retrying the same request will not help. Type: Warning.
	EventReasonAggregatorRejected = "AggregatorRejected"

	// EventReasonAggregatorError is emitted on 5xx or network errors from
	// dbaas-aggregator. Indicates a transient failure; the controller will
	// retry with exponential backoff. Type: Warning.
	EventReasonAggregatorError = "AggregatorError"

	// EventReasonOperationTerminated is emitted when dbaas-aggregator reports
	// status=TERMINATED for a polling operation. This occurs when the aggregator
	// pod is restarted mid-operation or when an admin explicitly cancels the
	// operation via the terminate API. It is NOT a spec error — the controller
	// clears the stale trackingID and resubmits automatically. Type: Warning.
	EventReasonOperationTerminated = "OperationTerminated"

	// ReasonSucceeded is used as the Stalled condition reason on successful
	// reconcile. It signals that the controller is not stalled because the
	// last operation completed successfully.
	ReasonSucceeded = "Succeeded"
)

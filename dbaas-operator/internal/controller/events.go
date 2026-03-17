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
//   - Tense: past for one-off successes ("Registered"), present participle for
//     ongoing problems that repeat on each reconcile ("Unauthorized")
const (
	// EventReasonRegistered is emitted when the database is successfully
	// registered with dbaas-aggregator. Type: Normal.
	EventReasonRegistered = "Registered"

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
)

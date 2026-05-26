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

// Package webhook hosts HTTP receivers exposed by the operator for
// server-to-server notifications from collaborators in the same cluster.
//
// The rotation webhook in particular accepts RotationOccurred /
// RestoreCompleted events from dbaas-aggregator. Authentication uses the
// platform's tokenverifier library, which validates Kubernetes service
// account tokens via OIDC against the cluster's API server.
package webhook

import (
	"context"
	"errors"
	"fmt"
	"strings"

	jwttoken "github.com/netcracker/qubership-core-lib-go/v3/security/token"
	"github.com/netcracker/qubership-core-lib-go/v3/security/tokenverifier"
)

// AuthResult holds identity extracted from a successfully validated
// Kubernetes service account token.
type AuthResult struct {
	// Subject is the canonical Kubernetes subject string in the form
	// "system:serviceaccount:<namespace>:<serviceAccountName>". Useful for
	// audit logging and any caller-level authorization checks layered on
	// top of the audience check performed by the underlying Verifier.
	Subject string

	// Namespace is the namespace claim of the token's service account.
	Namespace string

	// ServiceAccount is the name of the service account associated with the token.
	ServiceAccount string
}

// Authenticator validates an HTTP Authorization header and returns the
// authenticated identity. It is defined as an interface so handlers can be
// unit-tested with a mock implementation that does not require a real
// OIDC discovery against the Kubernetes API server.
type Authenticator interface {
	Authenticate(ctx context.Context, authHeader string) (AuthResult, error)
}

// ErrUnauthenticated is returned when the Authorization header is missing,
// malformed, or carries an invalid token. Handlers translate this into HTTP 401.
var ErrUnauthenticated = errors.New("unauthenticated")

// bearerPrefix is the prefix expected on the Authorization header value.
const bearerPrefix = "Bearer "

// AudienceDBaaSOperator is the canonical OIDC audience the operator's
// rotation webhook expects on inbound bearer tokens. Callers in the same
// cluster (dbaas-aggregator and any future subscriber) must project their
// service account token with this audience; tokens carrying a different aud
// claim are rejected at the JWT parser stage inside the underlying Verifier.
//
// This constant is the single source of truth for the audience name on the
// operator side. The matching value must appear in:
//   - the calling pod's projected volume serviceAccountToken.audience
//     (a Helm value in the caller's chart, e.g. dbaas-aggregator);
//   - the rotation webhook Service / RBAC documentation shipped with the
//     operator's Helm chart.
//
// TODO: When qubership-core-lib-go/security/tokensource gains a matching
// AudienceDBaaSOperator constant alongside AudienceDBaaS, replace this with
// an import of that symbol so every subscriber across the platform references
// a single definition.
const AudienceDBaaSOperator = "dbaas-operator"

// kubernetesAuthenticator is the production implementation backed by the
// platform tokenverifier library and Kubernetes OIDC discovery.
type kubernetesAuthenticator struct {
	verifier tokenverifier.Verifier
}

// NewKubernetesAuthenticator constructs an Authenticator that verifies
// Kubernetes service account tokens against the cluster's OIDC provider.
//
//   - audience is the value the calling service must request when projecting
//     its token. For the rotation webhook the standard value is
//     AudienceDBaaSOperator. The audience is forwarded to
//     tokenverifier.NewKubernetesVerifier, which configures the underlying
//     JWT parser with jwt.WithAudience(audience); tokens whose aud claim does
//     not contain this value are rejected by the parser.
//
// Constructing the verifier performs OIDC issuer discovery against the
// in-cluster API server, so this call must be made from a pod with a mounted
// service account token.
//
// Authorization is limited to the audience check: callers that successfully
// project a dbaas-operator-audience token are trusted. This matches the
// platform's typical single-tenant operations cluster, where the audience
// already restricts callers to pods that explicitly opted in via projected
// volume configuration. Multi-tenant deployments that need defense in depth
// can layer a subject allow-list on top via a tokenverifier.Validation
// passed to NewKubernetesVerifier.
func NewKubernetesAuthenticator(ctx context.Context, audience string) (Authenticator, error) {
	if audience == "" {
		return nil, errors.New("audience must not be empty")
	}
	v, err := tokenverifier.NewKubernetesVerifier(ctx, audience)
	if err != nil {
		return nil, fmt.Errorf("create kubernetes token verifier: %w", err)
	}
	return &kubernetesAuthenticator{verifier: v}, nil
}

// newAuthenticatorForTest wraps an arbitrary Verifier — used by unit tests to
// substitute a mock that does not perform OIDC discovery.
func newAuthenticatorForTest(v tokenverifier.Verifier) Authenticator {
	return &kubernetesAuthenticator{verifier: v}
}

// Authenticate parses the Authorization header, verifies the bearer token via
// the underlying Verifier, and returns the extracted identity. Errors are
// wrapped with ErrUnauthenticated so callers can map them to HTTP 401.
func (a *kubernetesAuthenticator) Authenticate(ctx context.Context, authHeader string) (AuthResult, error) {
	if authHeader == "" {
		return AuthResult{}, fmt.Errorf("%w: missing Authorization header", ErrUnauthenticated)
	}
	raw, ok := strings.CutPrefix(authHeader, bearerPrefix)
	if !ok || raw == "" {
		return AuthResult{}, fmt.Errorf("%w: Authorization header must use the Bearer scheme", ErrUnauthenticated)
	}

	t, err := a.verifier.Verify(ctx, raw)
	if err != nil {
		return AuthResult{}, fmt.Errorf("%w: %v", ErrUnauthenticated, err)
	}

	ns, _ := jwttoken.GetNamespace(t)
	sa, _ := jwttoken.GetServiceAccountName(t)
	return AuthResult{
		Subject:        jwttoken.GetKubernetesSubject(ns, sa),
		Namespace:      ns,
		ServiceAccount: sa,
	}, nil
}

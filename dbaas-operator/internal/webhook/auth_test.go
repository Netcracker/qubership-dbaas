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

package webhook

import (
	"context"
	"errors"
	"testing"

	"github.com/golang-jwt/jwt/v5"
	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"
)

// stubVerifier lets tests substitute the OIDC-backed verifier with a
// deterministic implementation that returns whatever token/error the test
// pre-configures.
type stubVerifier struct {
	token *jwt.Token
	err   error

	// gotToken captures the raw token passed in to assert wiring.
	gotToken string
}

func (s *stubVerifier) Verify(_ context.Context, rawToken string) (*jwt.Token, error) {
	s.gotToken = rawToken
	return s.token, s.err
}

// kubernetesJWT builds a parsed *jwt.Token (not signed) with the minimal
// kubernetes.io claim shape Authenticate needs to extract identity from.
func kubernetesJWT(namespace, serviceAccount string) *jwt.Token {
	claims := jwt.MapClaims{
		"kubernetes.io": map[string]any{
			"namespace": namespace,
			"serviceaccount": map[string]any{
				"name": serviceAccount,
				"uid":  "11111111-1111-1111-1111-111111111111",
			},
		},
	}
	return &jwt.Token{Claims: claims, Valid: true}
}

func TestWebhook(t *testing.T) {
	RegisterFailHandler(Fail)
	RunSpecs(t, "Webhook Suite")
}

var _ = Describe("Authenticator", func() {
	Describe("NewKubernetesAuthenticator", func() {
		It("rejects an empty audience at construction time", func() {
			_, err := NewKubernetesAuthenticator(context.Background(), "")
			Expect(err).To(MatchError(ContainSubstring("audience")))
		})
	})

	Describe("Authenticate header parsing", func() {
		var auth Authenticator
		var stub *stubVerifier

		BeforeEach(func() {
			stub = &stubVerifier{token: kubernetesJWT("dbaas", "dbaas-aggregator")}
			auth = newAuthenticatorForTest(stub)
		})

		It("returns ErrUnauthenticated when the header is empty", func() {
			_, err := auth.Authenticate(context.Background(), "")
			Expect(err).To(MatchError(ErrUnauthenticated))
			Expect(stub.gotToken).To(BeEmpty(), "verifier must not be invoked for a missing header")
		})

		It("returns ErrUnauthenticated when the scheme is not Bearer", func() {
			_, err := auth.Authenticate(context.Background(), "Basic dXNlcjpwYXNz")
			Expect(err).To(MatchError(ErrUnauthenticated))
			Expect(stub.gotToken).To(BeEmpty())
		})

		It("returns ErrUnauthenticated when the Bearer token is empty", func() {
			_, err := auth.Authenticate(context.Background(), "Bearer ")
			Expect(err).To(MatchError(ErrUnauthenticated))
			Expect(stub.gotToken).To(BeEmpty())
		})

		It("forwards the raw token to the verifier and returns the parsed identity", func() {
			result, err := auth.Authenticate(context.Background(), "Bearer abc.def.ghi")
			Expect(err).NotTo(HaveOccurred())
			Expect(stub.gotToken).To(Equal("abc.def.ghi"))
			Expect(result).To(Equal(AuthResult{
				Subject:        "system:serviceaccount:dbaas:dbaas-aggregator",
				Namespace:      "dbaas",
				ServiceAccount: "dbaas-aggregator",
			}))
		})
	})

	Describe("Authenticate verifier outcomes", func() {
		It("wraps verifier errors as ErrUnauthenticated", func() {
			stub := &stubVerifier{err: errors.New("signature invalid")}
			auth := newAuthenticatorForTest(stub)

			_, err := auth.Authenticate(context.Background(), "Bearer x")
			Expect(err).To(MatchError(ErrUnauthenticated))
			Expect(err.Error()).To(ContainSubstring("signature invalid"))
		})

		It("returns empty Subject/Namespace/ServiceAccount for non-Kubernetes tokens", func() {
			// A token without the kubernetes.io claim — extraction yields empty
			// strings; the Subject becomes "system:serviceaccount::". The audience
			// check inside the Verifier should have prevented this from reaching
			// us in practice, but Authenticate stays robust if a future Verifier
			// implementation forwards such a token.
			plain := &jwt.Token{Claims: jwt.MapClaims{"sub": "user@example.com"}, Valid: true}
			stub := &stubVerifier{token: plain}
			auth := newAuthenticatorForTest(stub)

			result, err := auth.Authenticate(context.Background(), "Bearer x")
			Expect(err).NotTo(HaveOccurred())
			Expect(result.Namespace).To(BeEmpty())
			Expect(result.ServiceAccount).To(BeEmpty())
			Expect(result.Subject).To(Equal("system:serviceaccount::"))
		})
	})

})

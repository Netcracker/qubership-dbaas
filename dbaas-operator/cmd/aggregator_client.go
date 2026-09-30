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

package main

import (
	"context"

	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	"github.com/netcracker/qubership-core-lib-go/v3/security"
	"sigs.k8s.io/controller-runtime/pkg/manager"

	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

// newAggregatorClient returns the dbaas-aggregator client for mode. In legacy mode the client uses Basic Auth with the
// credentials from securityDir, and the returned runnable reloads them when the Secret changes. In hybrid and k8s
// modes the client sends the Kubernetes token with the dbaas audience, and the runnable is nil.
func newAggregatorClient(log logging.Logger, mode security.M2MAuthMode, aggregatorURL, securityDir string) (*aggregatorclient.AggregatorClient, manager.Runnable) {
	switch mode {
	case security.M2MAuthModeHybrid, security.M2MAuthModeK8s:
		log.Infof("dbaas-aggregator client configured url=%v auth=m2m-token", aggregatorURL)
		return aggregatorclient.NewAggregatorClient(aggregatorURL), nil
	default:
		username, password := loadAggregatorCredentials(log, securityDir)
		aggregator := aggregatorclient.NewBasicAuthClient(aggregatorURL, username, password)
		credentialWatcher := manager.RunnableFunc(func(ctx context.Context) error {
			return watchCredentials(ctx, logging.GetLogger("dbaas-operator"), securityDir, aggregator)
		})
		log.Infof("dbaas-aggregator client configured url=%v auth=basic username=%v", aggregatorURL, username)
		return aggregator, credentialWatcher
	}
}

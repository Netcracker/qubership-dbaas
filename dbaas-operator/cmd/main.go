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
	"crypto/tls"
	"flag"
	"os"
	"strings"
	"time"

	// Import all Kubernetes client auth plugins (e.g. Azure, GCP, OIDC, etc.)
	// to ensure that exec-entrypoint and run can make use of them.
	_ "k8s.io/client-go/plugin/pkg/client/auth"

	"k8s.io/apimachinery/pkg/runtime"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	clientgoscheme "k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/util/workqueue"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/cache"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/healthz"
	"sigs.k8s.io/controller-runtime/pkg/manager"
	"sigs.k8s.io/controller-runtime/pkg/metrics/filters"
	metricsserver "sigs.k8s.io/controller-runtime/pkg/metrics/server"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	dbaasv1alpha1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1alpha1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/controller"
	// +kubebuilder:scaffold:imports
)

var (
	scheme   = runtime.NewScheme()
	setupLog = logging.GetLogger("dbaas-operator")
)

func init() {
	utilruntime.Must(clientgoscheme.AddToScheme(scheme))

	utilruntime.Must(dbaasv1alpha1.AddToScheme(scheme))
	// +kubebuilder:scaffold:scheme
}

// nolint:gocyclo
func main() {
	var metricsAddr string
	var metricsCertPath, metricsCertName, metricsCertKey string
	var enableLeaderElection bool
	var probeAddr string
	var secureMetrics bool
	var enableHTTP2 bool
	var watchNamespaces string
	var backoffBaseDelay time.Duration
	var backoffMaxDelay time.Duration
	var tlsOpts []func(*tls.Config)
	flag.StringVar(&metricsAddr, "metrics-bind-address", "0", "The address the metrics endpoint binds to. "+
		"Use :8443 for HTTPS or :8080 for HTTP, or leave as 0 to disable the metrics service.")
	flag.StringVar(&probeAddr, "health-probe-bind-address", ":8081", "The address the probe endpoint binds to.")
	flag.BoolVar(&enableLeaderElection, "leader-elect", false,
		"Enable leader election for controller manager. "+
			"Enabling this will ensure there is only one active controller manager.")
	flag.BoolVar(&secureMetrics, "metrics-secure", true,
		"If set, the metrics endpoint is served securely via HTTPS. Use --metrics-secure=false to use HTTP instead.")
	flag.StringVar(&metricsCertPath, "metrics-cert-path", "",
		"The directory that contains the metrics server certificate.")
	flag.StringVar(&metricsCertName, "metrics-cert-name", "tls.crt", "The name of the metrics server certificate file.")
	flag.StringVar(&metricsCertKey, "metrics-cert-key", "tls.key", "The name of the metrics server key file.")
	flag.BoolVar(&enableHTTP2, "enable-http2", false,
		"If set, HTTP/2 will be enabled for the metrics server")
	flag.StringVar(&watchNamespaces, "watch-namespaces", "",
		"Comma-separated list of namespaces to watch. Empty string means all namespaces (cluster-scoped).")
	flag.DurationVar(&backoffBaseDelay, "backoff-base-delay", 1*time.Second,
		"Initial delay for exponential backoff when a reconcile error occurs. "+
			"Doubles on each consecutive failure up to --backoff-max-delay.")
	flag.DurationVar(&backoffMaxDelay, "backoff-max-delay", 5*time.Minute,
		"Maximum delay cap for exponential backoff on reconcile errors.")
	flag.Parse()

	// if the enable-http2 flag is false (the default), http/2 should be disabled
	// due to its vulnerabilities. More specifically, disabling http/2 will
	// prevent from being vulnerable to the HTTP/2 Stream Cancellation and
	// Rapid Reset CVEs. For more information see:
	// - https://github.com/advisories/GHSA-qppj-fm5r-hxr3
	// - https://github.com/advisories/GHSA-4374-p667-p6c8
	disableHTTP2 := func(c *tls.Config) {
		setupLog.Info("Disabling HTTP/2")
		c.NextProtos = []string{"http/1.1"}
	}


	if !enableHTTP2 {
		tlsOpts = append(tlsOpts, disableHTTP2)
	}

	// Metrics endpoint is enabled in 'config/default/kustomization.yaml'. The Metrics options configure the server.
	// More info:
	// - https://pkg.go.dev/sigs.k8s.io/controller-runtime@v0.23.1/pkg/metrics/server
	// - https://book.kubebuilder.io/reference/metrics.html
	metricsServerOptions := metricsserver.Options{
		BindAddress:   metricsAddr,
		SecureServing: secureMetrics,
		TLSOpts:       tlsOpts,
	}

	if secureMetrics {
		// FilterProvider is used to protect the metrics endpoint with authn/authz.
		// These configurations ensure that only authorized users and service accounts
		// can access the metrics endpoint. The RBAC are configured in 'config/rbac/kustomization.yaml'. More info:
		// https://pkg.go.dev/sigs.k8s.io/controller-runtime@v0.23.1/pkg/metrics/filters#WithAuthenticationAndAuthorization
		metricsServerOptions.FilterProvider = filters.WithAuthenticationAndAuthorization
	}

	// If the certificate is not specified, controller-runtime will automatically
	// generate self-signed certificates for the metrics server. While convenient for development and testing,
	// this setup is not recommended for production.
	//
	// TODO(user): If you enable certManager, uncomment the following lines:
	// - [METRICS-WITH-CERTS] at config/default/kustomization.yaml to generate and use certificates
	// managed by cert-manager for the metrics server.
	// - [PROMETHEUS-WITH-CERTS] at config/prometheus/kustomization.yaml for TLS certification.
	if len(metricsCertPath) > 0 {
		setupLog.Info("Initializing metrics certificate watcher using provided certificates",
			"metrics-cert-path", metricsCertPath, "metrics-cert-name", metricsCertName, "metrics-cert-key", metricsCertKey)

		metricsServerOptions.CertDir = metricsCertPath
		metricsServerOptions.CertName = metricsCertName
		metricsServerOptions.KeyName = metricsCertKey
	}

	// ── dbaas-aggregator client ───────────────────────────────────────────────
	aggregatorURL := os.Getenv("DBAAS_AGGREGATOR_URL")
	if aggregatorURL == "" {
		aggregatorURL = "http://dbaas-aggregator:8080"
	}
	securityDir := os.Getenv("DBAAS_SECURITY_CONFIGURATION_LOCATION")
	if securityDir == "" {
		securityDir = "/etc/dbaas/security"
	}
	aggregatorUsername := os.Getenv("DBAAS_AGGREGATOR_USERNAME")
	if aggregatorUsername == "" {
		aggregatorUsername = "cluster-dba"
	}
	aggregatorPassword := loadAggregatorCredentials(setupLog, securityDir, aggregatorUsername)
	aggregator := aggregatorclient.NewAggregatorClient(
		aggregatorURL,
		aggregatorUsername,
		aggregatorPassword,
	)
	setupLog.Infof("dbaas-aggregator client configured url=%v username=%v", aggregatorURL, aggregatorUsername)

	// Build the cache options: restrict to specific namespaces if requested.
	var cacheOpts cache.Options
	if watchNamespaces != "" {
		nsMap := make(map[string]cache.Config)
		for _, ns := range strings.Split(watchNamespaces, ",") {
			ns = strings.TrimSpace(ns)
			if ns != "" {
				nsMap[ns] = cache.Config{}
			}
		}
		cacheOpts.DefaultNamespaces = nsMap
		setupLog.Infof("watching specific namespaces namespaces=%v", watchNamespaces)
	} else {
		setupLog.Info("watching all namespaces (cluster-scoped)")
	}

	mgr, err := ctrl.NewManager(ctrl.GetConfigOrDie(), ctrl.Options{
		Scheme:                 scheme,
		Metrics:                metricsServerOptions,
		HealthProbeBindAddress: probeAddr,
		LeaderElection:         enableLeaderElection,
		LeaderElectionID:       "0bafbe61.netcracker.com",
		Cache:                  cacheOpts,
		// LeaderElectionReleaseOnCancel defines if the leader should step down voluntarily
		// when the Manager ends. This requires the binary to immediately end when the
		// Manager is stopped, otherwise, this setting is unsafe. Setting this significantly
		// speeds up voluntary leader transitions as the new leader don't have to wait
		// LeaseDuration time first.
		//
		// In the default scaffold provided, the program ends immediately after
		// the manager stops, so would be fine to enable this option. However,
		// if you are doing or is intended to do any operation such as perform cleanups
		// after the manager stops then its usage might be unsafe.
		LeaderElectionReleaseOnCancel: true,
	})
	if err != nil {
		setupLog.Errorf("Failed to start manager: %v", err)
		os.Exit(1)
	}

	ddCtrlOpts := ctrlcontroller.Options{
		RateLimiter: workqueue.NewTypedItemExponentialFailureRateLimiter[reconcile.Request](
			backoffBaseDelay, backoffMaxDelay,
		),
	}
	if err := (&controller.DatabaseDeclarationReconciler{
		Client:     mgr.GetClient(),
		Scheme:     mgr.GetScheme(),
		Aggregator: aggregator,
		Recorder:   mgr.GetEventRecorderFor("databasedeclaration"),
	}).SetupWithManager(mgr, ddCtrlOpts); err != nil {
		setupLog.Errorf("Failed to create controller controller=DatabaseDeclaration: %v", err)
		os.Exit(1)
	}
	dpCtrlOpts := ctrlcontroller.Options{
		RateLimiter: workqueue.NewTypedItemExponentialFailureRateLimiter[reconcile.Request](
			backoffBaseDelay, backoffMaxDelay,
		),
	}
	if err := (&controller.DbPolicyReconciler{
		Client:     mgr.GetClient(),
		Scheme:     mgr.GetScheme(),
		Aggregator: aggregator,
		Recorder:   mgr.GetEventRecorderFor("dbpolicy"),
	}).SetupWithManager(mgr, dpCtrlOpts); err != nil {
		setupLog.Errorf("Failed to create controller controller=DbPolicy: %v", err)
		os.Exit(1)
	}
	edbCtrlOpts := ctrlcontroller.Options{
		RateLimiter: workqueue.NewTypedItemExponentialFailureRateLimiter[reconcile.Request](
			backoffBaseDelay, backoffMaxDelay,
		),
	}
	setupLog.Infof("backoff configured base=%v max=%v", backoffBaseDelay, backoffMaxDelay)
	if err := (&controller.ExternalDatabaseDeclarationReconciler{
		Client:     mgr.GetClient(),
		Scheme:     mgr.GetScheme(),
		Aggregator: aggregator,
		Recorder:   mgr.GetEventRecorderFor("externaldatabasedeclaration"),
	}).SetupWithManager(mgr, edbCtrlOpts); err != nil {
		setupLog.Errorf("Failed to create controller controller=ExternalDatabaseDeclaration: %v", err)
		os.Exit(1)
	}
	// +kubebuilder:scaffold:builder

	// Register the credential watcher so it shares the manager's lifecycle.
	// When Kubernetes updates the mounted Secret, the watcher reloads credentials
	// without requiring a pod restart.
	if err := mgr.Add(manager.RunnableFunc(func(ctx context.Context) error {
		return watchCredentials(ctx, logging.GetLogger("dbaas-operator"), securityDir, aggregatorUsername, aggregator)
	})); err != nil {
		setupLog.Errorf("Failed to register credential watcher: %v", err)
		os.Exit(1)
	}

	if err := mgr.AddHealthzCheck("healthz", healthz.Ping); err != nil {
		setupLog.Errorf("Failed to set up health check: %v", err)
		os.Exit(1)
	}
	if err := mgr.AddReadyzCheck("readyz", healthz.Ping); err != nil {
		setupLog.Errorf("Failed to set up ready check: %v", err)
		os.Exit(1)
	}

	setupLog.Info("Starting manager")
	if err := mgr.Start(ctrl.SetupSignalHandler()); err != nil {
		setupLog.Errorf("Failed to run manager: %v", err)
		os.Exit(1)
	}
}

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
	"flag"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	// Import all Kubernetes client auth plugins (e.g. Azure, GCP, OIDC, etc.)
	// to ensure that exec-entrypoint and run can make use of them.
	_ "k8s.io/client-go/plugin/pkg/client/auth"
	"k8s.io/client-go/rest"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/runtime"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	clientgoscheme "k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/tools/record"
	"k8s.io/client-go/util/workqueue"
	"k8s.io/klog/v2"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/cache"
	"sigs.k8s.io/controller-runtime/pkg/client"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/healthz"
	"sigs.k8s.io/controller-runtime/pkg/manager"
	httpserver "sigs.k8s.io/controller-runtime/pkg/metrics/server"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/baseproviders/xrequestid"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxmanager"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	_ "github.com/netcracker/qubership-core-lib-go/v3/memlimit"
	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/controller"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/poller"
	// +kubebuilder:scaffold:imports
)

var (
	scheme   = runtime.NewScheme()
	setupLog = logging.GetLogger("dbaas-operator")
)

func init() {
	utilruntime.Must(clientgoscheme.AddToScheme(scheme))

	utilruntime.Must(dbaasv1.AddToScheme(scheme))
	// +kubebuilder:scaffold:scheme
}

// nolint:gocyclo
func main() {
	ctxmanager.Register([]ctxmanager.ContextProvider{
		xrequestid.XRequestIdProvider{},
	})

	var httpAddr string
	var enableLeaderElection bool
	var probeAddr string
	var backoffBaseDelay time.Duration
	var backoffMaxDelay time.Duration
	flag.StringVar(&httpAddr, "http-bind-address", ":8080",
		"Address the operator's HTTP server binds to. Hosts the Prometheus /metrics endpoint.")
	flag.StringVar(&probeAddr, "health-probe-bind-address", ":8081", "The address the probe endpoint binds to.")
	flag.BoolVar(&enableLeaderElection, "leader-elect", false,
		"Enable leader election for controller manager. "+
			"Enabling this will ensure there is only one active controller manager.")
	flag.DurationVar(&backoffBaseDelay, "backoff-base-delay", 1*time.Second,
		"Initial delay for exponential backoff when a reconcile error occurs. "+
			"Doubles on each consecutive failure up to --backoff-max-delay.")
	flag.DurationVar(&backoffMaxDelay, "backoff-max-delay", 5*time.Minute,
		"Maximum delay cap for exponential backoff on reconcile errors.")
	flag.Parse()

	// Route both controller-runtime (logr) and client-go (klog) through the platform
	// logger. Without klog.SetLogger, client-go internals (leader election, transport)
	// log to stderr in klog's default glog format (I0622.../E0622...), which does not
	// match the Qubership log format the rest of the operator uses.
	logrLogger := newLogrLogger("dbaas-operator")
	ctrl.SetLogger(logrLogger)
	klog.SetLogger(logrLogger)

	// The manager HTTP listener hosts /metrics; the operator exposes no
	// application endpoint of its own.
	httpServerOpts := httpserver.Options{
		BindAddress: httpAddr,
	}

	cloudNamespace := os.Getenv("CLOUD_NAMESPACE")
	if cloudNamespace == "" {
		setupLog.Errorf("CLOUD_NAMESPACE env var is not set — ownership checks will not work correctly")
		os.Exit(1)
	}
	setupLog.Infof("operator namespace cloud-namespace=%v", cloudNamespace)

	aggregatorURL := os.Getenv("DBAAS_AGGREGATOR_URL")
	if aggregatorURL == "" {
		aggregatorURL = "http://dbaas-aggregator:8080"
	}

	// Authentication mode mirrors the aggregator's KUBERNETES_M2M_ENABLED flag:
	//   true  → Kubernetes projected service-account token (Bearer / M2M);
	//   false → HTTP Basic Auth with credentials from the mounted security Secret.
	// The aggregator rejects a Bearer token outright when M2M is disabled, so the
	// operator must match the cluster's setting. Defaults to false (Basic Auth).
	m2mEnabled := strings.EqualFold(os.Getenv("KUBERNETES_M2M_ENABLED"), "true")
	var aggregator *aggregatorclient.AggregatorClient
	var credentialWatcher manager.Runnable
	if m2mEnabled {
		aggregator = aggregatorclient.NewAggregatorClient(aggregatorURL)
		setupLog.Infof("dbaas-aggregator client configured url=%v auth=m2m-token", aggregatorURL)
	} else {
		// Basic Auth: read username/password from the mounted operator credentials
		// Secret (dbaas-operator-aggregator-credentials at securityDir).
		username, password := loadAggregatorCredentials(setupLog, securityDir)
		aggregator = aggregatorclient.NewBasicAuthClient(aggregatorURL, username, password)
		// Reload credentials on Secret rotation without a pod restart.
		credentialWatcher = manager.RunnableFunc(func(ctx context.Context) error {
			return watchCredentials(ctx, logging.GetLogger("dbaas-operator"), securityDir, aggregator)
		})
		setupLog.Infof("dbaas-aggregator client configured url=%v auth=basic username=%v", aggregatorURL, username)
	}

	eventsEnabled := strings.EqualFold(os.Getenv("K8S_EVENTS_ENABLED"), "true")
	setupLog.Infof("Kubernetes event recording enabled=%v", eventsEnabled)

	setupLog.Info("watching all namespaces (cluster-scoped)")

	// When events are disabled, the LeaderElectionConfig's transport
	// silently swallows event POSTs. This will prevent the leader
	// election recorder from logging "Server rejected event" errors
	baseConfig := ctrl.GetConfigOrDie()
	var leaderElectionConfig *rest.Config
	if !eventsEnabled {
		leaderElectionConfig = rest.CopyConfig(baseConfig)
		leaderElectionConfig.WrapTransport = silentEventsTransport
	}

	// PermanentBalancingRule is operator-namespace-only, so its informer is
	// scoped to CLOUD_NAMESPACE. All other CRs remain cluster-wide.
	cacheOpts := cache.Options{}
	if cloudNamespace != "" {
		cacheOpts.ByObject = map[client.Object]cache.ByObject{
			&dbaasv1.PermanentBalancingRule{}: {
				Namespaces: map[string]cache.Config{cloudNamespace: {}},
			},
		}
	}

	mgr, err := ctrl.NewManager(baseConfig, ctrl.Options{
		Scheme:                 scheme,
		Metrics:                httpServerOpts,
		HealthProbeBindAddress: probeAddr,
		Cache:                  cacheOpts,
		LeaderElection:         enableLeaderElection,
		LeaderElectionID:       "0bafbe61.netcracker.com",
		LeaderElectionConfig:   leaderElectionConfig,
		// Secrets are fetched directly from the API server on each reconcile
		// and do not need to be cached. Caching all Secrets cluster-wide
		// would load the entire cluster's secret store into memory, causing OOM.
		Client: client.Options{
			Cache: &client.CacheOptions{
				DisableFor: []client.Object{&corev1.Secret{}},
			},
		},
		// The process exits when the manager stops, so releasing the leader lease
		// on cancel is safe and speeds voluntary leader transitions.
		LeaderElectionReleaseOnCancel: true,
	})
	if err != nil {
		setupLog.Errorf("Failed to start manager: %v", err)
		os.Exit(1)
	}

	ctrlOpts := ctrlcontroller.Options{
		RateLimiter: workqueue.NewTypedItemExponentialFailureRateLimiter[reconcile.Request](
			backoffBaseDelay, backoffMaxDelay,
		),
	}
	setupLog.Infof("backoff configured base=%v max=%v", backoffBaseDelay, backoffMaxDelay)

	ownershipResolver := ownership.NewOwnershipResolver(cloudNamespace, mgr.GetClient())
	controller.RegisterResourceMetrics(mgr.GetClient(), ownershipResolver, cloudNamespace)
	if err := mgr.Add(&ownershipWarmupRunnable{resolver: ownershipResolver}); err != nil {
		setupLog.Errorf("Failed to register ownership warmup runnable: %v", err)
		os.Exit(1)
	}

	edbChecker := ownership.NewKindChecker(mgr.GetClient(), func() *dbaasv1.ExternalDatabaseList { return &dbaasv1.ExternalDatabaseList{} })
	dpChecker := ownership.NewKindChecker(mgr.GetClient(), func() *dbaasv1.DatabaseAccessPolicyList { return &dbaasv1.DatabaseAccessPolicyList{} })
	ddChecker := ownership.NewKindChecker(mgr.GetClient(), func() *dbaasv1.InternalDatabaseList { return &dbaasv1.InternalDatabaseList{} })
	microserviceRuleChecker := ownership.NewKindChecker(mgr.GetClient(), func() *dbaasv1.MicroserviceBalancingRuleList { return &dbaasv1.MicroserviceBalancingRuleList{} })
	namespaceRuleChecker := ownership.NewKindChecker(mgr.GetClient(), func() *dbaasv1.NamespaceBalancingRuleList { return &dbaasv1.NamespaceBalancingRuleList{} })
	dsChecker := ownership.NewKindChecker(mgr.GetClient(), func() *dbaasv1.DatabaseSecretClaimList { return &dbaasv1.DatabaseSecretClaimList{} })
	// PermanentBalancingRule is intentionally excluded: it is an operator-namespace
	// resource decoupled from NamespaceBinding, so it never blocks a (tenant)
	// NamespaceBinding deletion.
	blockingChecker := ownership.NewCompositeChecker(
		edbChecker,
		dpChecker,
		ddChecker,
		dsChecker,
		microserviceRuleChecker,
		namespaceRuleChecker,
	)
	if err := (&controller.NamespaceBindingReconciler{
		Client:      mgr.GetClient(),
		Scheme:      mgr.GetScheme(),
		Recorder:    recorderFor(mgr, "namespacebinding", eventsEnabled),
		MyNamespace: cloudNamespace,
		Ownership:   ownershipResolver,
		Checker:     blockingChecker,
	}).SetupWithManager(mgr, ctrlOpts); err != nil {
		setupLog.Errorf("Failed to create controller controller=NamespaceBinding: %v", err)
		os.Exit(1)
	}

	externalDatabaseReconciler := &controller.ExternalDatabaseReconciler{
		Client:     mgr.GetClient(),
		Scheme:     mgr.GetScheme(),
		Aggregator: aggregator,
		Recorder:   recorderFor(mgr, "externaldatabase", eventsEnabled),
		Ownership:  ownershipResolver,
	}
	// ExternalDatabase picks up referenced credential Secret changes through a
	// periodic resync. Override the default interval for faster rotation pickup.
	if v := os.Getenv("DBAAS_EXTERNAL_DATABASE_RESYNC_INTERVAL"); v != "" {
		if d, perr := time.ParseDuration(v); perr == nil && d > 0 {
			externalDatabaseReconciler.ResyncInterval = d
		} else {
			setupLog.Infof("Ignoring invalid DBAAS_EXTERNAL_DATABASE_RESYNC_INTERVAL=%q, using default", v)
		}
	}
	if err := externalDatabaseReconciler.SetupWithManager(mgr, ctrlOpts); err != nil {
		setupLog.Errorf("Failed to create controller controller=ExternalDatabase: %v", err)
		os.Exit(1)
	}

	if err := (&controller.DatabaseAccessPolicyReconciler{
		Client:     mgr.GetClient(),
		Scheme:     mgr.GetScheme(),
		Aggregator: aggregator,
		Recorder:   recorderFor(mgr, "databaseaccesspolicy", eventsEnabled),
		Ownership:  ownershipResolver,
	}).SetupWithManager(mgr, ctrlOpts); err != nil {
		setupLog.Errorf("Failed to create controller controller=DatabaseAccessPolicy: %v", err)
		os.Exit(1)
	}

	if err := (&controller.InternalDatabaseReconciler{
		Client:     mgr.GetClient(),
		Scheme:     mgr.GetScheme(),
		Aggregator: aggregator,
		Recorder:   recorderFor(mgr, "internaldatabase", eventsEnabled),
		Ownership:  ownershipResolver,
	}).SetupWithManager(mgr, ctrlOpts); err != nil {
		setupLog.Errorf("Failed to create controller controller=InternalDatabase: %v", err)
		os.Exit(1)
	}

	if err := (&controller.BalancingRuleReconciler{
		Client:      mgr.GetClient(),
		Scheme:      mgr.GetScheme(),
		Aggregator:  aggregator,
		Recorder:    recorderFor(mgr, "balancingrule", eventsEnabled),
		Ownership:   ownershipResolver,
		MyNamespace: cloudNamespace,
	}).SetupWithManager(mgr, ctrlOpts); err != nil {
		setupLog.Errorf("Failed to create controller controller=BalancingRule: %v", err)
		os.Exit(1)
	}

	if err := (&controller.DatabaseSecretClaimReconciler{
		Client:     mgr.GetClient(),
		Scheme:     mgr.GetScheme(),
		Aggregator: aggregator,
		Recorder:   recorderFor(mgr, "databasesecretclaim", eventsEnabled),
		Ownership:  ownershipResolver,
	}).SetupWithManager(mgr, ctrlOpts); err != nil {
		setupLog.Errorf("Failed to create controller controller=DatabaseSecretClaim: %v", err)
		os.Exit(1)
	}

	// +kubebuilder:scaffold:builder

	if err := mgr.AddHealthzCheck("healthz", healthz.Ping); err != nil {
		setupLog.Errorf("Failed to set up health check: %v", err)
		os.Exit(1)
	}
	if err := mgr.AddReadyzCheck("readyz", healthz.Ping); err != nil {
		setupLog.Errorf("Failed to set up ready check: %v", err)
		os.Exit(1)
	}

	// Leader-only loop that pulls dbaas-aggregator's changed-databases feed and
	// patches the AnnotationRotationTrigger annotation on each affected
	// DatabaseSecretClaim CR, waking its reconcile. The in-memory cursor is
	// backed by startup reconciliation and the per-CR safety-net requeue.
	pollInterval := 30 * time.Second
	if v := os.Getenv("DBAAS_ROTATION_POLL_INTERVAL"); v != "" {
		if d, perr := time.ParseDuration(v); perr == nil && d > 0 {
			pollInterval = d
		} else {
			setupLog.Infof("Ignoring invalid DBAAS_ROTATION_POLL_INTERVAL=%q, using default %v", v, pollInterval)
		}
	}
	if err := mgr.Add(&poller.RotationPoller{
		Client:   mgr.GetClient(),
		Source:   aggregator,
		Interval: pollInterval,
		Limit:    poller.DefaultLimit,
	}); err != nil {
		setupLog.Errorf("Failed to register rotation poller: %v", err)
		os.Exit(1)
	}
	setupLog.Infof("Rotation poller registered interval=%v", pollInterval)

	// In Basic Auth mode, reload the aggregator credentials when the mounted
	// Secret is updated, so a password rotation is picked up without a restart.
	if credentialWatcher != nil {
		if err := mgr.Add(credentialWatcher); err != nil {
			setupLog.Errorf("Failed to register credential watcher: %v", err)
			os.Exit(1)
		}
		setupLog.Info("Credential watcher registered (basic-auth mode)")
	}

	setupLog.Info("Starting manager")
	if err := mgr.Start(ctrl.SetupSignalHandler()); err != nil {
		setupLog.Errorf("Failed to run manager: %v", err)
		os.Exit(1)
	}
}

// ownershipWarmupRunnable pre-populates the ownership cache before the
// controller loops start.  It runs on every pod (no leader election) so that
// workload reconcilers can make ownership decisions immediately after startup
// without incurring per-object API round-trips.
type ownershipWarmupRunnable struct {
	resolver *ownership.OwnershipResolver
}

func (r *ownershipWarmupRunnable) NeedLeaderElection() bool { return false }

func (r *ownershipWarmupRunnable) Start(ctx context.Context) error {
	if err := r.resolver.WarmupOwnershipCache(ctx); err != nil {
		// Non-fatal: the resolver falls back to per-namespace API calls.
		setupLog.Infof("Ownership cache warmup failed (non-fatal, will fall back to live lookups): %v", err)
	}
	return nil
}

// recorderFor returns a real EventRecorder when enabled, or a no-op recorder
// that silently drops all events when K8S_EVENTS_ENABLED=false.
func recorderFor(mgr ctrl.Manager, name string, enabled bool) record.EventRecorder {
	if enabled {
		return mgr.GetEventRecorderFor(name) //nolint:staticcheck
	}
	return noopRecorder{}
}

// silentEventsTransport is a transport.WrapperFunc that intercepts event POST
// requests and returns a fake 201 without hitting the API server.  Used as
// LeaderElectionConfig.WrapTransport when K8S_EVENTS_ENABLED=false, because
// the leader-election recorder is wired inside controller-runtime and bypasses
// the per-controller noopRecorder.
func silentEventsTransport(rt http.RoundTripper) http.RoundTripper {
	return silentEventsRT{rt}
}

type silentEventsRT struct{ wrapped http.RoundTripper }

func (s silentEventsRT) RoundTrip(req *http.Request) (*http.Response, error) {
	if strings.HasSuffix(req.URL.Path, "/events") {
		if req.Body != nil {
			_, _ = io.Copy(io.Discard, req.Body)
			_ = req.Body.Close()
		}
		return &http.Response{
			StatusCode: http.StatusCreated,
			Body:       io.NopCloser(strings.NewReader("{}")),
			Header:     make(http.Header),
			Request:    req,
		}, nil
	}
	return s.wrapped.RoundTrip(req)
}

// noopRecorder implements record.EventRecorder and silently drops all events.
// Used when K8S_EVENTS_ENABLED=false to avoid requiring events RBAC in
// restricted environments.
type noopRecorder struct{}

func (noopRecorder) Event(runtime.Object, string, string, string)          {}
func (noopRecorder) Eventf(runtime.Object, string, string, string, ...any) {}
func (noopRecorder) AnnotatedEventf(runtime.Object, map[string]string, string, string, string, ...any) {
}

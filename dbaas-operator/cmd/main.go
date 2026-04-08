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
	"os"
	"strings"
	"time"

	// Import all Kubernetes client auth plugins (e.g. Azure, GCP, OIDC, etc.)
	// to ensure that exec-entrypoint and run can make use of them.
	_ "k8s.io/client-go/plugin/pkg/client/auth"

	"k8s.io/apimachinery/pkg/runtime"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	clientgoscheme "k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/tools/record"
	"k8s.io/client-go/util/workqueue"
	ctrl "sigs.k8s.io/controller-runtime"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/healthz"
	metricsserver "sigs.k8s.io/controller-runtime/pkg/metrics/server"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/baseproviders/xrequestid"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxmanager"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	_ "github.com/netcracker/qubership-core-lib-go/v3/memlimit"
	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	dbaasv1alpha1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1alpha1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/controller"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
	// +kubebuilder:scaffold:imports
)

var (
	scheme   = runtime.NewScheme()
	setupLog = logging.GetLogger("dbaas-operator")
)

func init() {
	utilruntime.Must(clientgoscheme.AddToScheme(scheme))

	utilruntime.Must(dbaasv1.AddToScheme(scheme))
	utilruntime.Must(dbaasv1alpha1.AddToScheme(scheme))
	// +kubebuilder:scaffold:scheme
}

// nolint:gocyclo
func main() {
	ctxmanager.Register([]ctxmanager.ContextProvider{
		xrequestid.XRequestIdProvider{},
	})

	var metricsAddr string
	var enableLeaderElection bool
	var probeAddr string
	var backoffBaseDelay time.Duration
	var backoffMaxDelay time.Duration
	flag.StringVar(&metricsAddr, "metrics-bind-address", ":8080", "The address the metrics endpoint binds to.")
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

	ctrl.SetLogger(newLogrLogger("dbaas-operator"))

	metricsServerOptions := metricsserver.Options{
		BindAddress: metricsAddr,
	}

	// ── Operator namespace ────────────────────────────────────────────────────
	cloudNamespace := os.Getenv("CLOUD_NAMESPACE")
	if cloudNamespace == "" {
		setupLog.Errorf("CLOUD_NAMESPACE env var is not set — ownership checks will not work correctly")
		os.Exit(1)
	}
	setupLog.Infof("operator namespace cloud-namespace=%v", cloudNamespace)

	// ── dbaas-aggregator client ───────────────────────────────────────────────
	aggregatorURL := os.Getenv("DBAAS_AGGREGATOR_URL")
	if aggregatorURL == "" {
		aggregatorURL = "http://dbaas-aggregator:8080"
	}
	aggregator := aggregatorclient.NewAggregatorClient(aggregatorURL)
	setupLog.Infof("dbaas-aggregator client configured url=%v", aggregatorURL)

	setupLog.Info("watching all namespaces (cluster-scoped)")

	mgr, err := ctrl.NewManager(ctrl.GetConfigOrDie(), ctrl.Options{
		Scheme:                 scheme,
		Metrics:                metricsServerOptions,
		HealthProbeBindAddress: probeAddr,
		LeaderElection:         enableLeaderElection,
		LeaderElectionID:       "0bafbe61.netcracker.com",
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

	ctrlOpts := ctrlcontroller.Options{
		RateLimiter: workqueue.NewTypedItemExponentialFailureRateLimiter[reconcile.Request](
			backoffBaseDelay, backoffMaxDelay,
		),
	}
	setupLog.Infof("backoff configured base=%v max=%v", backoffBaseDelay, backoffMaxDelay)

	// ── Ownership resolver ────────────────────────────────────────────────────
	ownershipResolver := ownership.NewOwnershipResolver(cloudNamespace, mgr.GetClient())
	if err := mgr.Add(&ownershipWarmupRunnable{resolver: ownershipResolver}); err != nil {
		setupLog.Errorf("Failed to register ownership warmup runnable: %v", err)
		os.Exit(1)
	}

	alphaAPIsEnabled := strings.EqualFold(os.Getenv("ALPHA_APIS_ENABLED"), "true")
	eventsEnabled := strings.EqualFold(os.Getenv("K8S_EVENTS_ENABLED"), "true")
	setupLog.Infof("Kubernetes event recording enabled=%v", eventsEnabled)

	// ── NamespaceBinding controller (always enabled) ───────────────────────────
	edbChecker := ownership.NewKindChecker(
		mgr.GetClient(),
		func() *dbaasv1.ExternalDatabaseList { return &dbaasv1.ExternalDatabaseList{} },
		func(l *dbaasv1.ExternalDatabaseList) int { return len(l.Items) },
	)
	blockingChecker := ownership.NewCompositeChecker(edbChecker)
	if alphaAPIsEnabled {
		ddChecker := ownership.NewKindChecker(
			mgr.GetClient(),
			func() *dbaasv1alpha1.DatabaseDeclarationList { return &dbaasv1alpha1.DatabaseDeclarationList{} },
			func(l *dbaasv1alpha1.DatabaseDeclarationList) int { return len(l.Items) },
		)
		dpChecker := ownership.NewKindChecker(
			mgr.GetClient(),
			func() *dbaasv1alpha1.DbPolicyList { return &dbaasv1alpha1.DbPolicyList{} },
			func(l *dbaasv1alpha1.DbPolicyList) int { return len(l.Items) },
		)
		blockingChecker.Add(ddChecker)
		blockingChecker.Add(dpChecker)
	}
	if err := (&controller.NamespaceBindingReconciler{
		Client:      mgr.GetClient(),
		Scheme:      mgr.GetScheme(),
		Recorder:    recorderFor(mgr, "namespacebinding", eventsEnabled),
		MyNamespace: cloudNamespace,
		Ownership:   ownershipResolver,
		Checker:     blockingChecker,
	}).SetupWithManager(mgr, ctrlOpts, alphaAPIsEnabled); err != nil {
		setupLog.Errorf("Failed to create controller controller=NamespaceBinding: %v", err)
		os.Exit(1)
	}

	if err := (&controller.ExternalDatabaseReconciler{
		Client:     mgr.GetClient(),
		Scheme:     mgr.GetScheme(),
		Aggregator: aggregator,
		Recorder:   recorderFor(mgr, "externaldatabase", eventsEnabled),
		Ownership:  ownershipResolver,
	}).SetupWithManager(mgr, ctrlOpts); err != nil {
		setupLog.Errorf("Failed to create controller controller=ExternalDatabase: %v", err)
		os.Exit(1)
	}

	if alphaAPIsEnabled {
		setupLog.Info("Alpha APIs are enabled")

		if err := (&controller.DatabaseDeclarationReconciler{
			Client:     mgr.GetClient(),
			Scheme:     mgr.GetScheme(),
			Aggregator: aggregator,
			Recorder:   recorderFor(mgr, "databasedeclaration", eventsEnabled),
			Ownership:  ownershipResolver,
		}).SetupWithManager(mgr, ctrlOpts); err != nil {
			setupLog.Errorf("Failed to create controller controller=DatabaseDeclaration: %v", err)
			os.Exit(1)
		}
		if err := (&controller.DbPolicyReconciler{
			Client:     mgr.GetClient(),
			Scheme:     mgr.GetScheme(),
			Aggregator: aggregator,
			Recorder:   recorderFor(mgr, "dbpolicy", eventsEnabled),
			Ownership:  ownershipResolver,
		}).SetupWithManager(mgr, ctrlOpts); err != nil {
			setupLog.Errorf("Failed to create controller controller=DbPolicy: %v", err)
			os.Exit(1)
		}
	} else {
		setupLog.Info("Alpha APIs are disabled")
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

// noopRecorder implements record.EventRecorder and silently drops all events.
// Used when K8S_EVENTS_ENABLED=false to avoid requiring events RBAC in
// restricted environments.
type noopRecorder struct{}

func (noopRecorder) Event(runtime.Object, string, string, string)          {}
func (noopRecorder) Eventf(runtime.Object, string, string, string, ...any) {}
func (noopRecorder) AnnotatedEventf(runtime.Object, map[string]string, string, string, string, ...any) {
}

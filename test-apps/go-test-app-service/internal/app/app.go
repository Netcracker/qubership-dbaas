package app

import (
	"errors"
	"net/http"

	dbaasbase "github.com/netcracker/qubership-core-lib-go-dbaas-base-client/v3"
	"github.com/netcracker/qubership-core-lib-go-dbaas-base-client/v3/model/rest"
	pgdbaas "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4"
	pgmodel "github.com/netcracker/qubership-core-lib-go-dbaas-postgres-client/v4/model"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/baseproviders/tenant"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxmanager"

	"github.com/netcracker/qubership-dbaas/test-apps/go-test-app-service/internal/postgresmigrations"
)

// adminRole is the userRole for the role-scoped quadrants (Q2/Q4); it must match the userRole on the
// matching mounted secret. The no-role quadrants (Q1/Q3) leave it empty.
const adminRole = "admin"

// fixedTenant is pinned into the request context for the tenant-scoped quadrants (Q3/Q4), so the
// tenant classifier is a static {scope=tenant, tenantId=acme} that matches the mounted secret.
const fixedTenant = "acme"

// App resolves one DBaaS datasource per (scope × role) quadrant, each matched from its own mounted
// secret by (classifier, type, userRole):
//
//	service (Q1, no role)   serviceAdmin (Q2, admin)
//	tenant  (Q3, no role)   tenantAdmin  (Q4, admin)
type App struct {
	service      pgdbaas.Database
	serviceAdmin pgdbaas.Database
	tenant       pgdbaas.Database
	tenantAdmin  pgdbaas.Database
}

func New() *App {
	// Register the tenant context provider so the tenant-scoped datasources (Q3/Q4) can resolve
	// tenantId from the request context (pinned by the tenant endpoints). Without it the tenant
	// classifier panics in tenant.Of(ctx) — the Go equivalent of the Quarkus/Spring DefaultTenantProvider.
	ctxmanager.Register([]ctxmanager.ContextProvider{tenant.TenantProvider{}})

	pool := dbaasbase.NewDbaaSPool()
	pgClient := pgdbaas.NewClient(pool)
	migrations := postgresmigrations.Migrations()
	adminParams := rest.BaseDbParams{Role: adminRole}
	return &App{
		service:      pgClient.ServiceDatabase(pgmodel.DbParams{Migrations: migrations}),
		serviceAdmin: pgClient.ServiceDatabase(pgmodel.DbParams{Migrations: migrations, BaseDbParams: adminParams}),
		tenant:       pgClient.TenantDatabase(pgmodel.DbParams{Migrations: migrations}),
		tenantAdmin:  pgClient.TenantDatabase(pgmodel.DbParams{Migrations: migrations, BaseDbParams: adminParams}),
	}
}

func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", handleHealth)

	// Q1: service + no role
	mux.HandleFunc("/postgres/ping", handlePostgresPing(a.service, false))
	mux.HandleFunc("/postgres/connection-properties", handlePostgresConnectionProperties(a.service, false))
	mux.HandleFunc("/postgres/items", handlePostgresItems(a.service, false))
	// Q2: service + admin
	mux.HandleFunc("/postgres-admin/items", handlePostgresItems(a.serviceAdmin, false))
	// Q3: tenant + no role
	mux.HandleFunc("/postgres-tenant/items", handlePostgresItems(a.tenant, true))
	// Q4: tenant + admin
	mux.HandleFunc("/postgres-tenant-admin/items", handlePostgresItems(a.tenantAdmin, true))
	return mux
}

func handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, errors.New("method not allowed"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

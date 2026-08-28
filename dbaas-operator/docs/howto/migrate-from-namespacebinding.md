# Migrating from the Retired NamespaceBinding Model

Version 6.15.0 removes the `NamespaceBinding` CR. This guide is for clusters that still run live
`NamespaceBinding` objects; a greenfield install needs nothing from it.

For everything else about the operator — the CR reference, status model, RBAC, and configuration —
see [DBaaS Operator](DBaaS%20Operator.md).

---

## What changes

Earlier releases carried the operator assignment in a separate `NamespaceBinding` CR: one object per workload
namespace, always named `binding`, holding `spec.operatorNamespace`. Managed CRs carried no assignment of their own
and inherited it from the binding in their namespace. That kind is gone, along with its CRD and its chart template.
Every managed CR now declares its own `spec.operatorNamespace`, and the operator reconciles a CR only when that value
equals the operator's own `CLOUD_NAMESPACE`.

| Concern | Retired model | Current model |
|---------|---------------|---------------|
| Where the assignment lives | `NamespaceBinding/binding` in the workload namespace | `spec.operatorNamespace` on every managed CR |
| Granularity | Whole namespace | Individual CR |
| Changing it | Delete and recreate the binding | Delete and recreate the CR — the field is immutable |
| Guard against releasing it | `platform.dbaas.netcracker.com/binding-protection` finalizer on the binding | None; an unassigned CR is ignored |
| Effect of no assignment | Namespace unbound, CRs requeued until a binding appeared | CR is invisible to every operator and gets no status at all |

The chart ships no automated upgrade between the two models, so the path below is manual.

---

## Setting the operator namespace

`spec.operatorNamespace` is the namespace the dbaas-operator instance runs in. The operator compares it
against its own `CLOUD_NAMESPACE`, which the chart injects from the pod's namespace through the downward
API (`fieldRef: metadata.namespace` in
[`Deployment.yaml`](../../helm-templates/dbaas-operator/templates/Deployment.yaml)) — so the operator
side needs no configuration, and only the CR has to carry the value.

In this migration the value is the one recorded from each `NamespaceBinding` in step 1. For a service
adopting the operator later, it is the namespace the dbaas-operator Deployment is installed in.

Nothing fills the field in automatically: the operator defines no defaulting webhook, so whatever
applies the CR (Helm, a GitOps sync, `kubectl apply`) has to carry the value. The API server rejects a
managed CR that omits it, and validates it as an RFC-1123 label: `MinLength=1`, `MaxLength=63`,
lowercase alphanumerics and hyphens.

Do not confuse it with a `CLOUD_NAMESPACE` that another component defines for its own namespace, as
dbaas-aggregator does; the operator never reads those. A value pointing at a workload namespace leaves
the resource assigned to no operator.

How a chart supplies the value is the service's own choice — there is no platform-provided variable that
holds it. Prefer the explicit form below; the derived form is a convenience for charts that already
carry the aggregator URL.

**Explicit chart value.** The most direct form, and the one the sample charts in this repository use,
where the value is named `DBAAS_OPERATOR_NAMESPACE`. The name carries no special meaning — it is an
ordinary Helm value the deployment supplies:

```yaml
spec:
  operatorNamespace: '{{ .Values.DBAAS_OPERATOR_NAMESPACE }}'
```

**Derived from the aggregator address.** A chart that already carries the aggregator URL can take the
namespace out of it instead of adding a value:

```yaml
spec:
  operatorNamespace: {{ (index (splitList "." (first (splitList ":" (last (splitList "://" .Values.API_DBAAS_ADDRESS))))) 1) | quote }}
```

This reads the second DNS label of the aggregator's service name — the namespace dbaas-aggregator and the operator
are deployed in. It depends on `API_DBAAS_ADDRESS` holding that in-cluster service name: an ingress or gateway host
(`https://dbaas.example.com`) renders `example`, while a short in-namespace name (`http://dbaas-aggregator:8080`)
fails loudly with `error calling index: reflect: slice index out of range`.

A wrong value cannot be corrected in place: the field is immutable, so the resource has to be deleted and recreated.

---

## Migration procedure

Steps 1 and 2 have to run while the old operator is still deployed. The inventory in step 1 rests on
`kubectl get -A`, which returns only the namespaces the caller may read: a context scoped to a subset
yields a short inventory rather than an error, and step 2 then deletes bindings that were never
recorded. Use a context with cluster-wide read access.

1. **Record the existing assignments.** Each `NamespaceBinding` names its operator in
   `spec.operatorNamespace`. List the bindings across all namespaces and keep the namespace-to-operator
   mapping. The chart upgrade in step 4 removes the CRD and its objects, and nothing else records the
   assignment once they are gone.

2. **Release and delete the bindings.** Every binding carries the
   `platform.dbaas.netcracker.com/binding-protection` finalizer, and once the new operator is running no
   controller removes it. Deleting the CRD then stalls: it sits in `Terminating` under
   `customresourcecleanup.apiextensions.k8s.io` while the binding remains stored, which blocks the chart
   upgrade. Clear the finalizer on each binding before deleting it — a binding is always named `binding`
   and there is at most one per namespace:

   ```bash
   kubectl patch namespacebinding binding -n <workload-namespace> \
     --type=merge -p '{"metadata":{"finalizers":null}}'
   ```

3. **Set the field in the service charts.** For each chart that ships managed CRs, render
   `spec.operatorNamespace` on every CR template using the operator namespace recorded in step 1 — see
   [Setting the operator namespace](#setting-the-operator-namespace) for the two ways to source it. The
   [`go-test-app-service` chart](../../../test-apps/go-test-app-service/helm-templates/go-test-app-service) is a worked
   example of the explicit form. Edit the charts now, but deploy them after step 4: the old CRD has no such field in
   its schema, so an apply before the upgrade prunes the value without reporting anything. Skipping the edit makes the
   *next* deployment of the service fail at admission, because the re-render applies a CR without a required field.

4. **Upgrade the operator chart.** The new CRDs make `spec.operatorNamespace` required, and the `NamespaceBinding`
   CRD is dropped. CRs already stored without the field survive the upgrade and stay readable, but no operator
   reconciles them until step 5 — see [During the gap](#during-the-gap) for what that does and does not affect.

5. **Backfill the live CRs.** Adding the field to an object stored without it is accepted; the immutability rule only
   rejects a *later* change to a value that is already set. A merge patch carries the value:

   ```bash
   kubectl patch internaldatabase <name> -n <workload-namespace> \
     --type=merge -p '{"spec":{"operatorNamespace":"<operator-namespace>"}}'
   ```

   Repeat this for every managed kind present in the namespace: `externaldatabases`, `internaldatabases`,
   `databaseaccesspolicies`, `databasesecretclaims`, `microservicebalancingrules`, `namespacebalancingrules`,
   and `permanentbalancingrules`. Redeploying every service through Helm or Argo CD after step 4 achieves the same
   result, because the re-rendered manifests now carry the field. Patch directly for CRs that no chart owns, or to
   close the gap before the next deployment.

6. **Verify that nothing is left unassigned.** Every managed CR, across all seven kinds, should report a
   `spec.operatorNamespace`. The `dbaas_resource_unassigned` metric reports the same thing: one series per CR that
   does not match the exporting operator's `CLOUD_NAMESPACE`, carrying the CR's declared value in the
   `operator_namespace` label. A CR missed by step 5 appears there with that label empty. Where several operators
   run, each also reports the others' CRs, so filter on `operator_namespace`. See
   [DBaaS Operator Metrics](../monitoring/DBaaS%20Operator%20Metrics.md) for the full reference.

---

## During the gap

Between step 4 and step 5 the managed CRs are unassigned: the operator skips them before any other work, so they get
no reconcile, no status update and no Kubernetes event, and they drop out of the per-resource state gauges. Nothing
downstream changes — what is already registered in dbaas-aggregator is untouched.

Do not delete a balancing rule resource while it is unassigned. The eligibility check runs before the deletion
branch, so the operator never removes the rule's finalizer and the delete hangs until the CR is assigned again. The
other four kinds carry no finalizer and can be deleted and recreated freely.

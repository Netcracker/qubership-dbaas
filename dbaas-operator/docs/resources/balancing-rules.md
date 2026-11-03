# Balancing Rules

Balancing rules tell dbaas-aggregator on which physical database to create new logical databases. Each kind is a
singleton with a fixed name and holds a list of rules; edit `spec.rules` to add, change or remove a rule.

| Kind                        | `metadata.name`                | Lives in               | Places                                                  |
|-----------------------------|--------------------------------|------------------------|---------------------------------------------------------|
| `MicroserviceBalancingRule` | `microservice-balancing-rules` | A business namespace   | Databases of the listed microservices in that namespace |
| `NamespaceBalancingRule`    | `namespace-balancing-rules`    | A business namespace   | All databases of that namespace                         |
| `PermanentBalancingRule`    | `permanent-balancing-rules`    | The operator namespace | Databases of the listed namespaces                      |

The API server rejects any other name. dbaas-aggregator decides the placement when it creates a database.

## MicroserviceBalancingRule

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: MicroserviceBalancingRule
metadata:
  name: microservice-balancing-rules
  namespace: payments
spec:
  operatorNamespace: dbaas-system
  rules:
    - type: postgresql
      label: core_balancing_rule=core
      microservices:
        - control-plane
        - identity-provider
```

`label` selects physical databases by a label they report, in `key=value` form. A `type` and microservice pair may
appear only once.

## NamespaceBalancingRule

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: NamespaceBalancingRule
metadata:
  name: namespace-balancing-rules
  namespace: payments
spec:
  operatorNamespace: dbaas-system
  rules:
    - name: pg-payments
      type: postgresql
      physicalDatabaseId: postgresql-payments
      order: 10
```

- `name` is global in dbaas-aggregator: reusing a name from another namespace overwrites that rule.
- `order` is required. For the same type, the rule with the higher `order` wins; two rules can't share a `type` and
  an `order`.

## PermanentBalancingRule

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: PermanentBalancingRule
metadata:
  name: permanent-balancing-rules
  namespace: dbaas-system
spec:
  operatorNamespace: dbaas-system
  rules:
    - dbType: postgresql
      physicalDatabaseId: postgresql-prod-a
      namespaces:
        - payments
        - orders
```

`metadata.namespace` must equal `spec.operatorNamespace`. The listed namespaces need no resources of their own. A
`dbType` and namespace pair may appear only once.

## Removal and Deletion

`status.appliedRules` records what the operator applied. When you remove a rule from `spec.rules`, the operator
removes it from dbaas-aggregator; when you delete the resource, a finalizer removes all of its rules first.

During a Blue-Green switch of operators, keep the old operator running until these finalizers finish. While a
deletion is stuck, the status keeps its last value; see
[Troubleshooting](../status-and-troubleshooting.md#a-balancing-rule-is-stuck-in-terminating).

## Reasons

Besides the [shared reasons](../status-and-troubleshooting.md#shared-reasons):

| Reason                 | Condition    | Meaning                |
|------------------------|--------------|------------------------|
| `BalancingRuleApplied` | `Ready=True` | The rules were applied |

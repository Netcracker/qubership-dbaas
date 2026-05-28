# Balancing Rule Singleton Scenarios

Start from the base local samples:

```bash
kubectl apply -f config-dev/samples/local/dbaas-system-namespacebinding.yaml
kubectl apply -f config-dev/samples/local/dbnamespacebalancingrule.yaml
kubectl apply -f config-dev/samples/local/dbpermanentbalancingrule.yaml
kubectl apply -f config-dev/samples/local/dbmicroservicebalancingrule.yaml
```

Watch status:

```bash
kubectl get dbnamespacebalancingrules,dbpermanentbalancingrules,dbmicroservicebalancingrules -n dbaas-system -w
```

Apply add/remove updates:

```bash
kubectl apply -f config-dev/samples/local/balancing-rule-scenarios/microservice-add-rule.yaml
kubectl apply -f config-dev/samples/local/balancing-rule-scenarios/microservice-remove-rule.yaml

kubectl apply -f config-dev/samples/local/balancing-rule-scenarios/namespace-add-rule.yaml
kubectl apply -f config-dev/samples/local/balancing-rule-scenarios/namespace-remove-rule.yaml

kubectl apply -f config-dev/samples/local/balancing-rule-scenarios/permanent-add-rule.yaml
kubectl apply -f config-dev/samples/local/balancing-rule-scenarios/permanent-remove-rule.yaml
```

Apply duplicate scenarios. These should be accepted by the Kubernetes API, then
marked `InvalidConfiguration` by the operator:

```bash
kubectl apply -f config-dev/samples/local/balancing-rule-scenarios/microservice-duplicate-type-microservice.yaml
kubectl apply -f config-dev/samples/local/balancing-rule-scenarios/namespace-duplicate-name.yaml
kubectl apply -f config-dev/samples/local/balancing-rule-scenarios/namespace-duplicate-type-order.yaml
kubectl apply -f config-dev/samples/local/balancing-rule-scenarios/permanent-duplicate-dbtype-namespace.yaml
```

To return to the baseline, reapply:

```bash
kubectl apply -f config-dev/samples/local/dbnamespacebalancingrule.yaml
kubectl apply -f config-dev/samples/local/dbpermanentbalancingrule.yaml
kubectl apply -f config-dev/samples/local/dbmicroservicebalancingrule.yaml
```

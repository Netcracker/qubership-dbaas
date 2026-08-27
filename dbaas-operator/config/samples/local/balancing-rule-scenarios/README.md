# Balancing Rule Singleton Scenarios

Start from the base local samples:

```bash
kubectl apply -f config/samples/local/dbaas-system-namespacebinding.yaml
kubectl apply -f config/samples/local/namespacebalancingrule.yaml
kubectl apply -f config/samples/local/permanentbalancingrule.yaml
kubectl apply -f config/samples/local/microservicebalancingrule.yaml
```

Watch status:

```bash
kubectl get namespacebalancingrules,permanentbalancingrules,microservicebalancingrules -n dbaas-system -w
```

Apply add/remove updates:

```bash
kubectl apply -f config/samples/local/balancing-rule-scenarios/microservice-add-rule.yaml
kubectl apply -f config/samples/local/balancing-rule-scenarios/microservice-remove-rule.yaml

kubectl apply -f config/samples/local/balancing-rule-scenarios/namespace-add-rule.yaml
kubectl apply -f config/samples/local/balancing-rule-scenarios/namespace-remove-rule.yaml

kubectl apply -f config/samples/local/balancing-rule-scenarios/permanent-add-rule.yaml
kubectl apply -f config/samples/local/balancing-rule-scenarios/permanent-remove-rule.yaml
```

Apply duplicate scenarios. These should be accepted by the Kubernetes API, then
marked `InvalidConfiguration` by the operator:

```bash
kubectl apply -f config/samples/local/balancing-rule-scenarios/microservice-duplicate-type-microservice.yaml
kubectl apply -f config/samples/local/balancing-rule-scenarios/namespace-duplicate-name.yaml
kubectl apply -f config/samples/local/balancing-rule-scenarios/namespace-duplicate-type-order.yaml
kubectl apply -f config/samples/local/balancing-rule-scenarios/permanent-duplicate-dbtype-namespace.yaml
```

To return to the baseline, reapply:

```bash
kubectl apply -f config/samples/local/namespacebalancingrule.yaml
kubectl apply -f config/samples/local/permanentbalancingrule.yaml
kubectl apply -f config/samples/local/microservicebalancingrule.yaml
```

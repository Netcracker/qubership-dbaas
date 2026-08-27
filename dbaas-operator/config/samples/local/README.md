# Local balancing rule samples

These resources are scoped to the local `dbaas-system` namespace.

Apply the namespace binding first:

```bash
kubectl apply -f config/samples/local/dbaas-system-namespacebinding.yaml
```

Then apply the singleton namespace/permanent rule samples:

```bash
kubectl apply -f config/samples/local/namespacebalancingrule.yaml
kubectl apply -f config/samples/local/permanentbalancingrule.yaml
```

The microservice rule sample requires physical databases whose labels match the
configured entries, for example `zone=fast` and `tier=standard`:

```bash
kubectl apply -f config/samples/local/microservicebalancingrule.yaml
```

Check status:

```bash
kubectl get namespacebalancingrules,permanentbalancingrules,microservicebalancingrules -n dbaas-system
kubectl describe namespacebalancingrule namespace-balancing-rules -n dbaas-system
```

Additional singleton update and duplicate-validation scenarios are in:

```text
config/samples/local/balancing-rule-scenarios/
```

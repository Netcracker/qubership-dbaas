# Local balancing rule samples

These resources are scoped to the local `dbaas-system` namespace.

Apply the namespace binding first:

```bash
kubectl apply -f config-dev/samples/local/dbaas-system-namespacebinding.yaml
```

Then apply the singleton namespace/permanent rule samples:

```bash
kubectl apply -f config-dev/samples/local/dbnamespacebalancingrule.yaml
kubectl apply -f config-dev/samples/local/dbpermanentbalancingrule.yaml
```

The microservice rule sample requires physical databases whose labels match the
configured entries, for example `zone=fast` and `tier=standard`:

```bash
kubectl apply -f config-dev/samples/local/dbmicroservicebalancingrule.yaml
```

Check status:

```bash
kubectl get dbnamespacebalancingrules,dbpermanentbalancingrules,dbmicroservicebalancingrules -n dbaas-system
kubectl describe dbnamespacebalancingrule namespace-balancing-rules -n dbaas-system
```

Additional singleton update and duplicate-validation scenarios are in:

```text
config-dev/samples/local/balancing-rule-scenarios/
```

# Rotation Role Lookup

The rotation poller wakes every `DatabaseSecretClaim` that matches a changed database by `(classifier, type)`, and
ignores `userRole`. This page explains why.

## The Operator Can't Know the Effective Role

The changed-databases feed says that a database's credentials changed, not which role. Even with the role, the
operator couldn't map it to claims, because dbaas-aggregator resolves the effective role per request
(`DatabaseRolesService.getSupportRole`) from inputs the claim doesn't hold:

- **The `DatabaseAccessPolicy`.** An empty `spec.userRole` becomes the policy's `defaultRole`. A set `userRole` is
  accepted only when it is the `defaultRole` or in `additionalRole`.
- **Who asks.** When `originService` differs from the classifier's `microserviceName`, the role comes from the policy's
  `services` grants instead.
- **The global permission registry**, as a fallback.

So the same `userRole` on two claims can resolve to different roles, and editing a policy changes the effective role
of claims that didn't change. Mapping roles in the operator would duplicate the aggregator's logic and race its cache.

## The Cost of Waking Every Match

Each woken claim fetches its credentials again, and the aggregator resolves the role. A claim whose credentials didn't
change writes nothing, because the Secret update compares content. A classifier usually has one to three claims, one
per role, so a rotation costs a few extra reads and no Secret writes.

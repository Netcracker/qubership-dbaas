create table if not exists composite_namespace
(
    id                uuid primary key,
    baseline          varchar(128) not null,
    namespace         varchar(128) not null
);

CREATE INDEX composite_namespaces_baseline_idx ON composite_namespace(baseline);
CREATE UNIQUE INDEX composite_namespaces_baseline_namespace_uniq ON composite_namespace(baseline, namespace);
CREATE UNIQUE INDEX composite_namespaces_namespace_uniq ON composite_namespace(namespace);
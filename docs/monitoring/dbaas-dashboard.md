This chapter describes the DBaaS monitoring dashboard, metrics, and analysis.

To access the dashboard:

1. Navigate to the Grafana server, and log in using the provided credentials.
1. Select the **DBaaS Dashboard**. Some installations can have a different name.
1. Select the **Datasource**. By default, if metrics are not labeled by datasource, the value is set to `None`.
1. Select or type the **Namespace**. In this case, **Namespace** is the project name (namespace) from OpenShift that was specified during installation.
1. Select the time range.

# Metrics

This section describes metrics and their meanings.

* [Overview Section](#overview-section)
* [Adapter Statistics Section](#adapter-statistics-section)
* [JVM Heap Section](#jvm-heap-section)
* [Non Heap Section](#non-heap-section)
* [GC Section](#gc-section)
* [Threads Section](#threads-section)
* [JDBC Section](#jdbc-section)
* [HTTP Statistics Section](#http-statistics-section)
* [HTTP Errors Section](#http-errors-section)

## Overview Section

This section shows overall health and resource usage of the DBaaS aggregator.

### Health

It shows the current health status of the DBaaS aggregator pod. The health status can be one of the following:

* `OK` — The aggregator pod is running.
* `FATAL` — The aggregator pod is not running.
* `N/A` — No data is available.

### Uptime

It shows how long the DBaaS aggregator process has been running since the last restart.

### Current Threads

It shows the current number of live JVM threads in the DBaaS aggregator.

### Classes

It shows the current number of classes loaded in the DBaaS aggregator JVM.

### CPU Usage

It shows CPU consumption of the DBaaS aggregator pod. The graph displays three series:

* `usage` — Actual CPU usage rate.
* `limit` — CPU limit configured for the pod.
* `request` — CPU request configured for the pod.

### Committed Memory

It shows memory consumption of the DBaaS aggregator pod. The graph displays the following series:

* `Limits` — Memory limit configured for the pod.
* `Request` — Memory request configured for the pod.
* `Usage` — Maximum memory usage of the pod container.
* `Heap` — JVM heap memory currently used.
* `NonHeap` — JVM non-heap memory currently used.

### Open Files

It shows the number of open file descriptors in the DBaaS aggregator process (average for the current time interval).

### Databases Number By Registration

It shows the distribution of databases by registration status as a pie chart. The chart displays:

* `registered databases` — Databases created and registered via DBaaS.
* `not registered databases` — Databases that exist in the underlying storage but were not created by DBaaS (ghost databases).

Databases that are not registered in DBaaS can cause problems for backup and maintenance procedures. It is important to keep tracking such cases.

### Number Of Databases

It shows the count of databases over time. The graph displays three series:

* `total registered` — Total number of databases registered in DBaaS.
* `not registered` — Number of ghost databases not registered in DBaaS.
* `lost` — Number of databases that were registered in DBaaS but can no longer be found in the underlying storage.

### Adapter Health Status

It shows the health status of each registered DBaaS adapter over time. Each series represents one adapter identified by its type and identifier.

## Adapter Statistics Section

This section shows request and operation statistics for each DBaaS adapter.

### Number of Requests to Adapter by Operation

It shows the rate of requests sent to each adapter, grouped by operation type and result. A separate graph is shown for each adapter.

### Duration of Operations by Adapter

It shows the average duration of operations executed against each adapter, grouped by operation type. A separate graph is shown for each adapter.

### Number of Registered Databases per Adapter Type

It shows the total count of databases registered in DBaaS for each adapter type over time. The graph displays series for the following adapter types: PostgreSQL, MongoDB, Cassandra, Elasticsearch, and Redis.

### Number of Not Registered Databases per Adapter Type

It shows the count of ghost databases (existing in storage but not registered in DBaaS) for each adapter type over time.

### Number of Lost Databases per Adapter Type

It shows the count of lost databases (registered in DBaaS but not found in storage) for each adapter type over time.

## JVM Heap Section

This section shows memory usage details for each JVM heap memory pool.

### Heap Usage

It shows used, committed, and allocated memory for each heap memory pool (Eden Space, Survivor Space, Tenured Gen). A separate graph is shown for each pool.

## Non Heap Section

This section shows memory usage details for JVM non-heap memory pools and byte buffers.

### Non Heap Usage

It shows used, committed, and allocated memory for each non-heap memory pool (Code Cache, Metaspace). A separate graph is shown for each pool.

### Byte Buffers

It shows the used memory and total capacity for JVM off-heap byte buffers. A separate graph is shown for each buffer type (direct, mapped). Direct buffers are allocated via `ByteBuffer.allocateDirect()`.

## GC Section

This section shows garbage collection activity in the JVM.

### GC: Pauses

It shows the time spent on garbage collection per second, broken down by GC cause and action (minor and major GC).

### GC: Allocations

It shows the rate of memory allocation in the young generation heap pool between GC cycles.

## Threads Section

This section shows JVM thread statistics.

### Threads

It shows the live, daemon, and peak thread counts over time:

* `current` — Current number of live threads.
* `daemon` — Current number of daemon threads.
* `peak` — Peak number of live threads since the JVM started.

### Threads: State

It shows the number of threads in each JVM thread state (NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED).

## JDBC Section

This section shows connection pool statistics for the DBaaS aggregator database connections.

### Number Of Active Connections

It shows the number of active (in-use) JDBC connections per datasource and pod.

### Number Of Idle Connections

It shows the number of idle (available) JDBC connections per datasource and pod.

### Number Of Threads Waiting Connection

It shows the number of threads currently waiting to acquire a connection from the pool per datasource and pod.

### Number Of Leaks Detected

It shows the cumulative count of connection leak detections per datasource.

### Application Average Wait Connection Time

It shows the average time (in milliseconds) that application threads wait to acquire a JDBC connection per datasource and pod.

## HTTP Statistics Section

This section shows HTTP request statistics for successful requests.

### HTTP 2xx Responses

It shows the rate of HTTP responses with status code 2xx per endpoint URI.

### HTTP Endpoint Timings. Code 2xx

It shows the maximum execution time of HTTP requests per endpoint URI for requests that returned a 2xx status code.

## HTTP Errors Section

This section shows HTTP request statistics for error responses.

### HTTP Endpoint Statistics. Code: 4xx

It shows the rate of HTTP requests per endpoint URI that returned a 4xx status code.

### HTTP Endpoint Statistics. Code: 5xx

It shows the rate of HTTP requests per endpoint URI that returned a 5xx status code.

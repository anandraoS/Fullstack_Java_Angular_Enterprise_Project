# Runbook 08 - JVM memory problems

All commands: illustrative, not executed against a live cluster. Runtime is Java 17. Heap dumps contain customer data (synthetic in this project, real in any real deployment): treat them as confidential, store encrypted, delete after analysis.

## Symptoms

* `OOMKilled` pod restarts (container limit hit, exit code 137) or `java.lang.OutOfMemoryError: Java heap space` in logs.
* `jvm.memory.used{area="heap"}` close to `jvm.memory.max` and not falling after GC.
* Long or frequent GC pauses (`jvm.gc.pause`), latency spikes that correlate, throughput drop.
* Kafka consumers kicked from the group because a pause exceeded `max.poll.interval.ms` (lag and rebalances; runbook 02).

## Metrics and PromQL

```
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}
sum by (service) (jvm_memory_used_bytes{area="nonheap"})
rate(jvm_gc_pause_seconds_sum[5m]) / rate(jvm_gc_pause_seconds_count[5m])      # mean pause
max_over_time(jvm_gc_pause_seconds_max[5m])
rate(jvm_gc_pause_seconds_sum[5m])                                              # fraction of time paused; > 0.1 is a problem
jvm_gc_live_data_size_bytes                                                     # live set after major GC; rising = leak
container_memory_working_set_bytes{namespace="oms"} / on(pod) kube_pod_container_resource_limits{resource="memory"}
jvm_threads_live_threads
```

Reading it: a sawtooth heap that returns to a stable floor is healthy. A floor that creeps upward across GCs is a leak. Heap fine but container working set near limit means native/off-heap/metaspace/thread stacks or `-Xmx` set too close to the limit.

## Diagnostics

```bash
# What are the JVM flags and sizes?
kubectl -n oms exec deploy/order-service -- jcmd 1 VM.flags
kubectl -n oms exec deploy/order-service -- jcmd 1 VM.info | head -40
kubectl -n oms top pod

# GC activity (5 samples, 1 s)
kubectl -n oms exec deploy/order-service -- jstat -gcutil 1 1000 5
#   O (old gen %) pinned near 100 and FGC increasing => memory pressure / leak

# Class histogram: cheap, no full dump, shows top retained-by-count classes
kubectl -n oms exec deploy/order-service -- jcmd 1 GC.class_histogram | head -30

# Heap dump (pauses the JVM for seconds; do it on one pod you have removed from traffic, if possible)
kubectl -n oms exec deploy/order-service -- jcmd 1 GC.heap_dump /tmp/heap.hprof
kubectl -n oms cp order-service-<pod>:/tmp/heap.hprof ./heap.hprof     # then delete from the pod

# Threads (stack memory, runaway pools)
kubectl -n oms exec deploy/order-service -- jcmd 1 Thread.print | grep -c '^"'

# Native memory tracking only if the JVM was started with -XX:NativeMemoryTracking=summary
kubectl -n oms exec deploy/order-service -- jcmd 1 VM.native_memory summary
```

Preventive JVM flags worth having in every environment: `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps` (with a mounted, restricted volume), `-XX:MaxRAMPercentage=70` (leave room for metaspace, threads, direct buffers), `-Xlog:gc*:file=/tmp/gc.log`. Analyse dumps with Eclipse MAT: look at the dominator tree and "leak suspects".

## Root-cause tree

```
Memory pressure
|- Heap leak (live set grows)
|    |- unbounded collections / caches without eviction (in-memory rate limiter buckets keyed by customer/IP, product cache without max size)
|    |- unbounded list endpoint loading entities (code-review scenario 06)
|    |- large payloads: whole aggregates in events, big JSON bodies (limit is 64 KB by filter)
|    |- listener holding references (e.g. accumulating failed records in a list)
|    '- ThreadLocal/MDC not cleaned in pooled threads
|- Heap too small for workload (live set stable but near max) -> resize, not leak
|- Off-heap / native
|    |- Metaspace (class loader leak in dev tools), direct buffers (Netty in the gateway), thread stacks (thread-per-task pools)
|    '- container limit vs MaxRAMPercentage mismatch -> OOMKilled with a healthy heap
|- GC tuning symptoms: allocation rate high (N+1 loading, big result sets), humongous allocations (G1)
'- Load: traffic spike or retry storm
```

## Mitigation

1. Capture evidence (histogram, GC log, heap dump) from one pod **before** restarting it; the restart clears the problem for hours and destroys the proof.
2. Restart or roll pods one at a time to restore service; scale out if the live set is just too large for one pod.
3. Temporary relief: raise the memory limit and `MaxRAMPercentage` together; record it as a stopgap with an owner and date.
4. Identify the leak with the dump; fix (bound the cache, page the query, clean the ThreadLocal); add a test.
5. Gateway: check in-memory rate-limit bucket growth when `oms.redis.enabled=false` (the local default); in production Redis stores buckets.

## Prevention

* Every cache and in-memory map has a maximum size and an eviction policy; reviews ask "what bounds this?".
* Paged queries only; entities never accumulated in memory.
* Alerts: heap used > 85% after GC for 10 min, `jvm_gc_live_data_size_bytes` trend, GC pause fraction, OOMKilled restarts.
* Memory limits defined from measurement (k6 load profile in `performance/`), documented with the observed live set.
* `HeapDumpOnOutOfMemoryError` configured, with access control on the dump volume.

## Practice exercise

1. Local JVMs (no Docker). Start one service with a small heap by adding `-Xmx128m` to its launch (inline environment `JAVA_TOOL_OPTIONS=-Xmx128m` for that process only; do not export globally; the local scripts launch the JVMs for you; the small-heap exercise was not run, the demo JVMs are capped at 384 MB each).
2. Introduce the flawed unbounded listing from `docs/code-review/06-unbounded-pagination-n-plus-one.md` on a scratch branch, seed 100k orders using a throwaway script, and call the endpoint repeatedly.
3. Observe with `jstat -gcutil <pid> 1000` and `jcmd <pid> GC.class_histogram | head`. Capture a dump with `jcmd <pid> GC.heap_dump <path-outside-repo>` and open it in MAT (dominator tree).
4. Fix with paging and re-run: old gen stabilises.

## Regression test pointers

* Statement and result-size bound tests for list endpoints (exists): `OrderRepositoryTest#searchingAPageOfOrdersWithItemsTakesAConstantNumberOfStatements`, `PageRequestFactoryTest` (rejects oversize pages); see code-review scenario 06.
* Bounded-cache unit test (exists): `ProductCacheTest` `sizeIsBoundedAndATtlOfZeroDisablesCaching` (inventory-service `InMemoryProductCache`).
* k6 soak profile in `performance/` with an assertion on heap floor trend (documented procedure; not executed).

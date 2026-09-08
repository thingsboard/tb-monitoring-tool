# tb-monitoring

Blackbox (synthetic) monitoring tool for a ThingsBoard deployment. It acts as an ordinary
client: logs in, opens a WebSocket subscription, and sends test telemetry through each
configured transport (MQTT, CoAP, HTTP, LwM2M), then waits for that telemetry to arrive back
over WebSocket — an end-to-end round trip, not an internal health check.

When login/WS is down, it falls back to a weaker "accepted" check per transport (does the
transport itself acknowledge the message?), reported separately from the full end-to-end
signal so a fallback success can't mask or resolve a real incident.

Also monitors ThingsBoard PE's Integrations Framework (HTTP/CoAP/MQTT) the same way - a test
payload through the integration's endpoint, then a WS round trip. The same build works against
either CE or PE - it probes the target server's edition at startup and adjusts accordingly.
Integration checks are enabled by default but only meaningful against PE, so they're
automatically skipped when the target turns out to be CE - nothing to turn off by hand.

Exposes results as Slack notifications, with optional incident grouping/auto-resolution.

See `src/main/resources/tb-monitoring.yml` for the full list of config keys (env var name, default, and what it does — every key is documented there).

## Metrics

Every check (login, WS, each transport, each PE integration check) exports the same two gauges,
pushed via OTLP (`METRICS_OTLP_ENABLED=true`) and/or pulled from a Prometheus endpoint
(`METRICS_PROMETHEUS_ENABLED=true`, default port 9100, see `tb-monitoring.yml` for the rest of
the `METRICS_OTLP_*`/`METRICS_PROMETHEUS_*` knobs):

- `probe_success{domain,check,endpoint,kind,label}` — 1/0. `kind="probe"` is the full end-to-end
  check; `kind="accepted"` is the weaker transport-only fallback that runs when login/WS itself
  is down. A transport's own check failing sets this to 0; it only goes *absent* if login/WS died
  upstream and this check was never reached this cycle - alert on "absent OR 0", not just "0".
- `probe_duration_seconds{...,action}` — per-stage latency (`request`, `ws_update`, `connect`,
  `subscribe`). Disappears (not stale) once its stage stops running or passing.

Plus one heartbeat: `tb_monitoring_last_run_timestamp_seconds{domain,label}` - alert on
`time() - metric > N` to catch a dead prober itself, which `probe_success` can't (it only reports
on targets it actually got to check).

## Building

Requires JDK 25.

```bash
mvn package -DskipTests
```

`common:data`/`common:util`/`rest-client` are pinned to a fixed release published on
`repo.thingsboard.io` (not a SNAPSHOT) — this repo builds standalone, no need to clone or
build the main `thingsboard` monorepo. The pinned version carries a `PE` suffix (see
`pom.xml`) because the Integrations Framework monitoring below needs PE-only data classes
(`Integration`, `Converter`, `EntityGroup`) that the plain CE artifact doesn't have.

## Running

Minimum required config (everything else has a sane default — see the yml):

```bash
export REST_BASE_URL=http://your-tb-instance:8080
export WS_BASE_URL=ws://your-tb-instance:8080
export REST_AUTH_USERNAME=tenant@thingsboard.org
export REST_AUTH_PASSWORD=tenant
export MQTT_TRANSPORT_BASE_URL=tcp://your-tb-instance:1883
export HTTP_TRANSPORT_BASE_URL=http://your-tb-instance:8080
export COAP_TRANSPORT_BASE_URL=coap://your-tb-instance:5683

java -jar target/tb-monitoring-*.jar
```

JVM tuning (heap, GC, etc.) - set `JDK_JAVA_OPTIONS`, which the `java` launcher itself reads
natively (JDK 9+):

```bash
export JDK_JAVA_OPTIONS="-Xmx256m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError"
```

## Docker

```bash
mvn package -DskipTests
docker build -f docker/Dockerfile -t tb-monitoring .
docker run -d --env-file docker/.env --name tb-monitoring tb-monitoring
docker logs -f tb-monitoring
```

`docker/.env` has a starter set of variables — copy and edit it for your deployment.

Multi-arch (amd64/arm64) build — the jar is pure JVM bytecode, only the base image needs
both platforms:

```bash
docker buildx build --platform linux/amd64,linux/arm64 -f docker/Dockerfile -t <repo>:tag --push .
```

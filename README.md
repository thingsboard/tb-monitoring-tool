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
  `kind="rpc"` is the opt-in RPC round trip: it's only checked once the transport check passes, and
  goes absent while the transport check is failing.
- `probe_duration_ms{...,action}` — per-stage latency (`request`, `ws_update`, `connect`,
  `subscribe`). Disappears (not stale) once its stage stops running or passing.

Plus one heartbeat: `tb_monitoring_last_run_timestamp_seconds{domain,label}` - alert on
`time() - metric > N` to catch a dead prober itself, which `probe_success` can't (it only reports
on targets it actually got to check).

## RPC check

Each `monitoring.transports.{mqtt,http,coap,lwm2m}.targets[N]` accepts an optional
`rpc:` sub-block. When `rpc.enabled` is `true`, the monitoring loop runs an
extra round-trip on the same target per cycle, after the existing telemetry
uplink + WebSocket validation has succeeded.

```yaml
monitoring:
  transports:
    mqtt:
      request_timeout_ms: 4000
      targets:
        - base_url: 'tcp://${monitoring.domain}:1883'
          queue: 'Main'
          rpc:
            enabled: true
            request_timeout_ms: 4000
        - base_url: 'ssl://${monitoring.domain}:8883'   # secure variant
          queue: 'Main'
          rpc:
            enabled: true                                # inherits ssl + creds
```

### YAML keys

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `rpc.enabled` | bool | `false` | Opt in to the per-target RPC sub-check. |
| `rpc.request_timeout_ms` | int | transport `request_timeout_ms` | Sent through to ThingsBoard as the RPC `timeout`. Must be `< monitoring.rest.request_timeout_ms`. |

For LwM2M the default is `6000` ms because the Read goes through rule engine +
Leshan registration; the other transports default to `4000`. The default
`monitoring.rest.request_timeout_ms` is `8000` ms, leaving 2 s margin above
LwM2M; if you raise any per-target `rpc.request_timeout_ms` near or above the
REST timeout, raise `REST_REQUEST_TIMEOUT_MS` first or `initialize()` will
fail-fast at startup.

### Per-transport behaviour

| Transport | Device side | Cloud side | Assertion |
|-----------|-------------|------------|-----------|
| MQTT | Same Paho client subscribes `v1/devices/me/rpc/request/+` and publishes echoed `params` to `v1/devices/me/rpc/response/{id}` | `POST /api/rpc/twoway/{deviceId}` `{method:"monitoringCheck", params:{value:<uuid>}}` | echoed `value == uuid` |
| HTTP | Daemon long-poll thread per target: `GET /api/v1/{token}/rpc?timeout=1000`; on 200, `POST /api/v1/{token}/rpc/{id}` with the params | same as MQTT | echoed `value == uuid` |
| CoAP | Separate `CoapClient` opens an OBSERVE on `coap://host/api/v1/{token}/rpc`; on each notification, posts echoed params to `coap://host/api/v1/{token}/rpc/{id}` | same as MQTT | echoed `value == uuid` |
| LwM2M | None — the existing `Lwm2mClient` already serves `/3/0/0` via the standard LwM2M Read | `POST /api/rpc/twoway/{deviceId}` `{method:"Read", params:{key:"/3/0/0"}}` | response is non-blank |

### Failure attribution

RPC failures use a dedicated `RpcInfo` service key whose `getShortName()`
returns `"<transport.shortName> RPC"` (`"MQTT RPC"`, `"MQTT Foo RPC"` for
non-default queues, etc.). The IncidentManager header therefore distinguishes
`":red_circle: MQTT (n)"` from `":red_circle: MQTT RPC (m)"`. A telemetry
uplink failure short-circuits the RPC sub-check for that cycle, so a broken
transport will not double-count against both rows.

### Latencies

When the RPC check succeeds, the round-trip is reported under
`<key>RpcRoundTrip` (e.g. `mqttRpcRoundTrip`, `mqttFooRpcRoundTrip` for
non-default queues), alongside the existing `<key>Request` and `<key>WsUpdate`
keys for the same target. No latencies dashboard schema change is needed.

### Troubleshooting

- **`rpc.request_timeout_ms` ordering**: the RPC HTTP call goes through the
  same `monitoring.rest.request_timeout_ms` REST client. Keep
  `rpc.request_timeout_ms` strictly smaller so the device-side timeout fires
  first; otherwise the REST call will fail with `ResourceAccessException`
  before the RPC has a chance to time out cleanly.
- **HTTP polling thread leaks**: the long-poll thread is daemon and lifecycle-
  bound to `initClient` / `destroyClient`. If you see leaked threads, check
  whether a custom shutdown hook is bypassing `@PreDestroy`.
- **CoAP OBSERVE behind NAT**: the OBSERVE client uses the same Californium
  configuration as the telemetry client; if the telemetry check works but the
  RPC observe never delivers, check NAT/firewall on the CoAP UDP path.
- **LwM2M Read on `/3/0/0` returns blank**: the monitoring `Lwm2mClient`
  serves resource `0` from whatever was last sent via telemetry, so a blank
  result usually means telemetry never reached the device this cycle. Confirm
  the telemetry row is green first.

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

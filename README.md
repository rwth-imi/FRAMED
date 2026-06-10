
<img src="images/logo.png" alt="Logo" style="float: right; margin-left: 20px;">
<br style="clear: both"/>





[![Java CI with Maven](https://github.com/rwth-imi/FRAMED/actions/workflows/maven.yml/badge.svg)](https://github.com/rwth-imi/FRAMED/actions/workflows/maven.yml)
![Version](https://img.shields.io/badge/version-1.0.0--SNAPSHOT-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![License](https://img.shields.io/badge/license-GPL--2.0-blue)

FRAMED is a service-oriented software framework for acquiring, transforming, and reacting to
data streams from multiple sources — medical devices, sensors, replay files, and more. You
build an application by implementing a few **extension points** (services) and wiring them
together over an **event bus**, either declaratively from a JSON config or programmatically.
The architecture is highly modular and runs on a single edge device or across a distributed
setup; communication between services flows asynchronously over a `SocketEventBus`.

**Two audiences:**

- **Building an application on FRAMED?** This README is your guide — start at
  [Mental model](#mental-model) below.
- **Looking at the clinical decision-support case study?** It ships in this repository: see
  the runnable configurations in [`config/`](config/) and the paper under [Cite As](#cite-as).

## Documentation

- This guide (below) — using FRAMED as a framework.
- Generated API docs (Javadoc): **https://rwth-imi.github.io/FRAMED/docs/**

---

## Mental model

```
   sources            transforms              sinks
  ┌─────────┐        ┌──────────┐          ┌────────────┐
  │Protocol │─bus──▶ │ Parser   │─bus──▶   │ Writer     │
  │(device, │        │(raw→JSON │          │ Dispatcher │
  │ replay, │        │  events) │          │ (DB/file)  │
  │ socket) │        └──────────┘          └────────────┘
  └─────────┘             │                      ▲
                          └──▶ Reactor ─bus───────┘
                               (rules → derived events)
```

Everything is a **`Service`** that talks over an **`EventBus`** by publishing/subscribing to
named **channels** (addresses). FRAMED gives you:

| Extension point | Package | Role | You implement |
|---|---|---|---|
| `Service`    | `com.framed.core`        | base type for anything on the bus | `stop()` (optional) |
| `Protocol`   | `com.framed.io.protocol` | a data **source** (device, file, socket) | `connect()` |
| `Parser<T>`  | `com.framed.io.parser`   | turn raw input into bus events | `parse(T, String)` |
| `Writer<T>`  | `com.framed.io.writer`   | persist data to the filesystem | `write(T, String)` |
| `Dispatcher` | `com.framed.io.dispatch` | push data to an external **sink** (DB, JSONL) | `push(...)`, `pushBatch(...)` |
| `Reactor`    | `com.framed.arn`         | fire a reaction when input rules are met | `reactionFunction(Map)` |

The orchestrator (`com.framed.orchestrator`) instantiates these from config, and a
`DeploymentValidator` (`com.framed.core.spi`) can validate the assembled topology.

---

## Requirements

- **Java 21**
- **Maven 3.8+**
- FRAMED currently builds from source (GitHub Packages publishing is planned — see
  `docs/reusable-framework-plan.md`).

---

## 1. Add FRAMED to your project

### Now: build & install locally

From a clone of this repository, install the framework artifacts into your local Maven
repository (`~/.m2`):

```bash
# install just the SDK (and the parent POM it needs)
mvn -pl framed-core -am install

# …or install everything (core + communicator + streamer + cdss + app)
mvn install
```

Then depend on the SDK from your own project:

```xml
<dependency>
  <groupId>com.framed</groupId>
  <artifactId>framed-core</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`framed-core` is the only artifact you need to implement any extension point. Its sole
runtime dependency is `org.json`. The medical-device drivers (`framed-communicator`),
InfluxDB/JSONL sinks (`framed-streamer`), and clinical reactors (`framed-cdss`) are optional
add-ons you depend on only if you want their concrete implementations.

> **JPMS:** `framed-core` is a named module (`module com.framed.core`). If your project also
> uses `module-info.java`, add `requires com.framed.core;`. On the classpath it behaves as a
> normal jar.

### Later: GitHub Packages

Once published, you will add the `rwth-imi/FRAMED` GitHub Packages repository and the same
coordinates. (GitHub Packages requires authentication to pull; setup will be documented
when publishing lands.)

---

## 2. Two ways to run FRAMED

- **Embedded / programmatic** — you create an `EventBus`, instantiate your services with
  `new`, and manage the lifecycle yourself. Best for libraries, tests, and apps that already
  have a `main`. No config files involved.
- **Config-driven** — you describe services in `config/services.json`, start
  `com.framed.orchestrator.Main`, and FRAMED reflectively instantiates and wires everything.
  Best for deployable, reconfigurable pipelines.

Both use the exact same service classes; config-driven is just a reflective launcher on top.

---

## 3. Core concepts

### EventBus and channels

```java
public interface EventBus {
  void register(String address, Consumer<Object> handler);
  void register(String address, Consumer<Object> handler, DispatchMode perHandlerMode);
  void send(String address, Object message);      // point-to-point (first handler)
  void publish(String address, Object message);   // broadcast (all handlers)
  void shutdown();
}
```

Implementations:

- **`LocalEventBus`** (`com.framed.core.local`) — in-JVM, no networking. Ideal for embedding.
- **`SocketEventBus`** (`com.framed.core.remote`) — local dispatch **plus** forwarding to
  remote peers over a `Transport` (TCP/UDP). Used by the config-driven launcher.

`DispatchMode` (`com.framed.core.utils`) controls how local handlers run:
`SEQUENTIAL` (inline), `PARALLEL` (shared pool), `PER_HANDLER` (one FIFO thread per handler).

### Service

```java
public abstract class Service {
  protected EventBus eventBus;     // injected via constructor
  protected final Logger logger;
  public static String addressRegistry(String group);          // "<group>.addresses"
  protected void announceAddress(String group, String address); // see §3, address-discovery
  public void stop() { /* override to release resources */ }
}
```

**Convention:** a service does its wiring **in its constructor** — registers bus handlers,
opens connections, starts threads. There is no separate `start()`; construction *is* startup.
Override `stop()` for clean shutdown.

### The address-discovery protocol

Producers don't hard-wire which sink consumes them. Instead, a producer **announces** each
output channel under a group, and sinks **subscribe** to that group's registry to discover
channels dynamically:

```java
// producer side (in your Protocol/Parser/Reactor):
announceAddress("my-device", "Temperature.my-device.value");   // publishes to "my-device.addresses"
eventBus.publish("Temperature.my-device.value", payload);

// sink side (Dispatcher/Writer): subscribe to the registry to learn channels
eventBus.register(addressRegistry("my-device"), addr -> register((String) addr));
```

This is how `Dispatcher` and `Writer` bind to whatever channels a device emits without
being recompiled. Re-announcing on each publish lets late-joining sinks catch up.

---

## 4. Implementing the extension points

Minimal, compile-accurate skeletons. All constructors take `EventBus` (the orchestrator
injects it; embedded code passes it explicitly).

### A data source — `Protocol`

```java
import com.framed.core.EventBus;
import com.framed.io.protocol.Protocol;
import org.json.JSONObject;

public class RandomSensor extends Protocol {
  private final String channel;

  public RandomSensor(String id, EventBus eventBus) {
    super(id, eventBus);          // sets this.id, this.eventBus
    this.channel = "Temp." + id + ".value";
    connect();                    // start producing (convention: wire up in ctor)
  }

  @Override public void connect() {
    new Thread(() -> {
      while (true) {
        JSONObject dp = new JSONObject()
            .put("value", 20 + Math.random() * 5)
            .put("channelID", channel)
            .put("timestamp", java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                 .format(com.framed.core.utils.Timer.formatter));
        announceAddress(id, channel);   // let sinks discover this channel
        eventBus.publish(channel, dp);
        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
      }
    }, id + "-thread").start();
  }
}
```

### A transform — `Parser<T>`

```java
import com.framed.core.EventBus;
import com.framed.io.parser.Parser;

public class UppercaseParser extends Parser<String> {
  public UppercaseParser(EventBus eventBus, String inputChannel) {
    super(eventBus);
    eventBus.register(inputChannel, msg -> parse((String) msg, "device"));
  }
  @Override public void parse(String message, String deviceName) {
    String address = "parsed." + deviceName;
    announceAddress(deviceName, address);
    eventBus.publish(address, message.toUpperCase());
  }
}
```

### A reaction — `Reactor`

A `Reactor` watches input channels and fires `reactionFunction` once per evaluation cycle
when its **firing rules** are satisfied. It receives an immutable snapshot of the latest
value per channel (plus `"<channel>-timestamp"` → `Instant`).

```java
import com.framed.arn.Reactor;
import com.framed.core.EventBus;
import java.util.*;
import static com.framed.arn.RuleUtils.publishResult;

public class HighTempReactor extends Reactor {
  public HighTempReactor(EventBus eventBus, String id, String inputChannel) {
    // rules: one rule, fire when ≥1 new message arrives on inputChannel ("*")
    super(eventBus, id,
          List.of(Map.of(inputChannel, "*")),   // firingRules
          List.of(inputChannel),                 // inputChannels
          List.of("Temp-Alarm"));                // outputChannels
  }

  @Override public void reactionFunction(Map<String, Object> snapshot) {
    double v = ((Number) snapshot.get(getInputChannels().get(0))).doubleValue();
    int level = v > 24 ? 1 : 0;
    publishResult(eventBus, level, id, getOutputChannels(), lastLogicalFireTs);
  }
}
```

**Firing-rule tokens** (a rule is a `Map<channel, token>`; the rule list is OR, channels
within a rule are AND):

| Token  | Meaning |
|--------|---------|
| `"*"`  | ANY — at least one new message on the channel since this rule last fired |
| `"N"`  | AT_LEAST(N) — at least N new messages since last fired |
| `"r:v"`| REQUIRE_VALUE — a new message **and** latest value equals `v` |

### A sink — `Dispatcher`

```java
import com.framed.core.EventBus;
import com.framed.io.dispatch.Dispatcher;
import com.framed.io.dispatch.DataPoint;
import org.json.JSONArray;
import java.util.List;

public class StdoutDispatcher extends Dispatcher {
  public StdoutDispatcher(EventBus eventBus, JSONArray devices) {
    super(eventBus, devices);   // auto-subscribes to "<device>.addresses" discovery
  }
  @Override public void push(DataPoint<?> dp) { System.out.println(dp.toJsonString()); }
  @Override public void pushBatch(List<DataPoint<?>> batch) { batch.forEach(this::push); }
}
```

`Dispatcher` already subscribes to each configured device's address registry, converts bus
JSON messages into `DataPoint` records, and calls your `push` on a background worker with
retry/backoff. You only implement the destination.

### A `Writer<T>` follows the same shape — `super(path, eventBus)` then implement
`write(T data, String deviceName)`.

---

## 5. Running config-driven

### `config/communication.json` — the bus/transport

```json
{
  "type": "TCP",          // TCP | UDP
  "port": 4999,
  "peers": [              // optional: remote SocketEventBus instances to forward to
    { "host": "10.0.0.2", "port": 4999 }
  ]
}
```

### `config/services.json` — the services

Top-level keys are **sections**; the launcher instantiates these, in this order:
`Dispatchers`, `Devices`, `Writers`, `Parsers`, `Reactors`. Each section is an array of
service definitions. Every definition needs `class` and `id`; all other keys are
**constructor arguments**.

```json
{
  "Devices": [
    { "class": "com.example.RandomSensor", "id": "sensor-1" }
  ],
  "Reactors": [
    {
      "class": "com.example.HighTempReactor",
      "id": "temp-alarm",
      "inputChannel": "Temp.sensor-1.value"
    }
  ],
  "Dispatchers": [
    { "class": "com.example.StdoutDispatcher", "id": "stdout", "devices": ["sensor-1", "CDSS"] }
  ]
}
```

### How config maps to constructors (the `Factory` rules)

The `Factory` picks a public constructor where **every parameter is resolvable**:

- a parameter whose **name matches a JSON key** receives that value;
- a parameter of type **`EventBus`** is injected automatically;
- otherwise the constructor doesn't match.

Therefore:

- **Constructor parameter names must equal the JSON keys** (compile with `-parameters`,
  which the FRAMED parent POM already enables).
- JSON values arrive as their `org.json` types: arrays → `org.json.JSONArray`, objects →
  `JSONObject`, numbers → `Integer`/`double`, etc. Your constructor takes those types and
  adapts them (e.g. parse a `JSONArray` of channels into a `List<String>`).
- **All non-`EventBus` parameters must be present in the JSON** (including primitives like a
  `boolean atomic`), or that constructor won't be selected.

Example of a constructor designed for config (from `LimitClassificationReactor`):

```java
public LimitClassificationReactor(EventBus eventBus, String id,
                                  JSONArray firingRules, String inputChannel,
                                  JSONArray outputChannels, JSONArray limits, boolean atomic) {
  super(eventBus, id, parseFiringRulesJson(firingRules),
        List.of(inputChannel), parseChannelListJson(outputChannels), atomic);
  this.limits = parseLimitsJson(limits);
}
```

### Launch

Build the runnable assembly and run it from a directory containing `config/`:

```bash
mvn -pl framed-app -am package    # builds framed-app/target/framed-app-*-fat.jar
java -jar framed-app/target/framed-app-1.0.0-SNAPSHOT-fat.jar
```

`Main` reads `config/services.json` + `config/communication.json` (relative to the working
directory), builds the bus, instantiates each section, runs deployment validators, then
blocks until interrupted (a shutdown hook stops services cleanly).

> To launch **your own** app jar instead, depend on `framed-core` (+ any concrete modules)
> and either reuse `com.framed.orchestrator.Main` or call `Manager`/`Factory` yourself.
> `Manager.instantiate("YourSection")` lets you use custom section names.

### Custom implementations in the config launch

The launcher is not limited to FRAMED's built-in services. `Factory` does
`Class.forName(className)` against the **runtime classpath** and instantiates **any** public
`Service` subclass — including your own reactors, protocols, parsers, writers, and
dispatchers. Reference them by fully-qualified class name in `services.json` exactly like the
built-ins.

For a class to be config-loadable it must satisfy four requirements:

1. **Public class extending the right base** — e.g. `extends Reactor` (which extends
   `Service`); `Factory` casts the instance to `Service`.
2. **A public constructor** — `Factory` only scans public constructors.
3. **Constructor parameter names match the JSON keys, compiled with `-parameters`.** This is
   the most common pitfall: without `-parameters` the names become `arg0`, `arg1`, … and
   name-matching silently fails so the constructor is never selected. The FRAMED parent POM
   enables `-parameters`; **your build must enable it too** (`maven-compiler-plugin`
   `<compilerArgs><arg>-parameters</arg></compilerArgs>`).
4. **Placed under a section the launcher loads** (`Dispatchers`, `Devices`, `Writers`,
   `Parsers`, `Reactors`) — or instantiate a custom section yourself with
   `Manager.instantiate("YourSection")`.

```json
"Reactors": [
  {
    "class": "com.acme.MyCustomReactor",
    "id": "my-reactor",
    "inputChannel": "Temp.sensor-1.value",
    "atomic": true
  }
]
```

Three ways to get your class onto the launch classpath:

| Approach | When to use | How |
|---|---|---|
| **Your own assembly** *(recommended for a separate project)* | Building a project *on* FRAMED | Depend on `framed-core`, keep your services in your module, and build your own fat-jar that reuses `com.framed.orchestrator.Main` (or your own launcher). Run it from a directory with your `config/`. |
| **Add a module to this monorepo** | Extending FRAMED in-repo | Add your Maven module, make `framed-app` depend on it, rebuild the fat-jar — your classes get shaded in. |
| **Append your jar at launch** | Quick experiment against the shipped fat-jar | `java -cp "framed-app-1.0.0-SNAPSHOT-fat.jar:my-reactors.jar" com.framed.orchestrator.Main` (run from a dir containing `config/`). |

The shipped fat-jar runs on the classpath (its `module-info` is stripped), so reflective
instantiation of your classes is unrestricted. If a custom `Reactor` is loaded, the bundled
`com.framed.arn.ARN` validator automatically includes it in the acyclic-network check — no
extra wiring needed.

---

## 6. Running embedded (programmatic)

No config, no sockets — wire it up in code:

```java
import com.framed.core.EventBus;
import com.framed.core.local.LocalEventBus;
import com.framed.core.utils.DispatchMode;

public class App {
  public static void main(String[] args) {
    EventBus bus = new LocalEventBus(DispatchMode.PER_HANDLER);

    new RandomSensor("sensor-1", bus);                                  // source
    new HighTempReactor(bus, "temp-alarm", "Temp.sensor-1.value");      // reaction
    bus.register("Temp-Alarm", msg -> System.out.println("ALARM: " + msg));

    Runtime.getRuntime().addShutdownHook(new Thread(bus::shutdown));
  }
}
```

---

## 7. Validating the deployed topology

Implement `DeploymentValidator` to check the assembled set of services after instantiation
(e.g. structural constraints). Register it via `ServiceLoader`
(`META-INF/services/com.framed.core.spi.DeploymentValidator`), and the orchestrator
(`Manager.validate()`) runs it automatically.

```java
package com.example;
import com.framed.core.Service;
import com.framed.core.spi.DeploymentValidator;
import java.util.Collection;

public class MyValidator implements DeploymentValidator {
  @Override public void validate(Collection<Service> services) {
    // inspect services (instanceof your types); throw IllegalArgumentException if invalid
  }
}
```

FRAMED ships one such validator: `com.framed.arn.ARN`, which verifies that the deployed
`Reactor`s form an acyclic network.

---

## 8. Going distributed

Use `SocketEventBus` with a `Transport` (`TCPTransport`/`UDPTransport` or their NIO
variants) and add `Peer`s. Anything `publish`ed is also forwarded to peers, so services on
different nodes share channels transparently. The config-driven launcher does this for you
from `communication.json` (`type`, `port`, `peers`).

---

## Module reference

| Artifact | Depend on it for |
|---|---|
| `com.framed:framed-core` | the SDK — every extension point, both event buses, orchestrator. **Start here.** |
| `com.framed:framed-communicator` | ready-made Medibus/Viatom device drivers + replay protocol |
| `com.framed:framed-streamer` | ready-made InfluxDB / JSON-Lines dispatchers |
| `com.framed:framed-cdss` | the clinical decision-support reactors (case study) |
| `com.framed:framed-app` | runnable assembly (fat-jar + `Main`); not a library |

## Support 
Please write an issue to get support on your matters.

## Roadmap 
There are more default protocols to come, including IEEE SDC!
Also, we are at the state of developing an Alarm-CDSS on top of the data gathering layer (cf. Architecture).
## Contributing 🫶
Thank you for considering! Further information is coming soon.

## License ⚖️
This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 2.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with this program; if not, write to the Free Software Foundation, Inc.,
    51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

## Cite As

Freyer N, Röhrig R, Lipprandt M. An Open-Source Abstraction Framework for Biosignal and Medical Device Data. Stud Health Technol Inform. 2026 May 21;336:1808-1809. doi: 10.3233/SHTI260543. PMID: 42175214.

```
@article{freyer2026open,
  title={An Open-Source Abstraction Framework for Biosignal and Medical Device Data},
  author={Freyer, Nils and R{\"o}hrig, Rainer and Lipprandt, Myriam},
  journal={Studies in health technology and informatics},
  volume={336},
  pages={1808--1809},
  year={2026}
}
```

## Project status 
Running. ✅

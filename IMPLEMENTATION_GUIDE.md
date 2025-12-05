# ONOS Learning Bridge - Implementation Guide

This guide explains the architecture, data structures, and lifecycle of the Learning Bridge application. Use this to understand how the pieces fit together and how to implement the required functionality.

---

## 📖 Table of Contents

1. [Application Architecture](#application-architecture)
2. [ONOS Bundle Lifecycle](#onos-bundle-lifecycle)
3. [Data Structures](#data-structures)
4. [Flow Rule Management](#flow-rule-management)
5. [Helper Classes](#helper-classes)
6. [ONOS API Examples](#onos-api-examples)
7. [Testing Strategy](#testing-strategy)

---

## Application Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────┐
│                  LearningBridgeApp                          │
│  ┌───────────────────────────────────────────────────────┐  │
│  │            ONOS Service References                    │  │
│  │  - CoreService                                        │  │
│  │  - PacketService (packet-in/out)                      │  │
│  │  - FlowRuleService (manage flows)                     │  │
│  │  - FlowObjectiveService (high-level flow API)         │  │
│  │  - DeviceService (query switches)                     │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │           Application State                           │  │
│  │  - macTables (MAC learning)                           │  │
│  │  - activeDestinations (connection tracking)           │  │
│  │  - tcpConnections (TCP stats)                         │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │         LearningBridgeProcessor                       │  │
│  │  - Handles packet-in events                           │  │
│  │  - Learns MAC addresses                               │  │
│  │  - Enforces connection limits                         │  │
│  │  - Installs flow rules                                │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │         InternalFlowListener                          │  │
│  │  - Monitors flow removal events                       │  │
│  │  - Cleans up connection tracking                      │  │
│  │  - Logs TCP statistics                                │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Current Behavior (Hub Mode)

The starter template floods all packets to all ports, like a network hub:

```
Packet arrives → Extract info → Flood to all ports
```

### Target Behavior (Learning Bridge)

You will implement intelligent forwarding:

```
Packet arrives
    ↓
Learn source MAC → port mapping
    ↓
Check connection limit (if enabled)
    ↓
Look up destination MAC
    ↓
  ┌─────────┴─────────┐
  │                   │
Known              Unknown
  │                   │
  ↓                   ↓
Install           Flood
flow rule         packet
  ↓
Forward
packet
```

---

## ONOS Bundle Lifecycle

### OSGi Component Lifecycle

ONOS applications are OSGi bundles. Understanding the lifecycle is crucial:

```
┌─────────────────────────────────────────────────────────┐
│                  Bundle States                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  INSTALLED → RESOLVED → STARTING → ACTIVE              │
│                                        ↓                │
│                                    @Activate            │
│                                    Your code runs       │
│                                        ↓                │
│                                   Application           │
│                                   processes packets     │
│                                        ↓                │
│                                    @Deactivate          │
│                                    Cleanup              │
│                                        ↓                │
│  STOPPING → RESOLVED → UNINSTALLED                      │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### @Activate Method

Called when ONOS activates your bundle:

1. **Register application** - Get unique ApplicationId
2. **Add packet processor** - Start receiving packet-in events
3. **Add flow listener** - Start receiving flow events
4. **Request packets** - Tell ONOS to send packets to your app

### @Deactivate Method

Called when ONOS deactivates your bundle:

1. **Remove listeners** - Stop receiving events
2. **Remove packet processor** - Stop processing packets
3. **Remove flow rules** - Clean up installed flows
4. **Log statistics** - Save any final data

**Why this matters**: Proper cleanup prevents memory leaks and ensures smooth updates.

---

## Data Structures

### 1. MAC Learning Table

**Purpose**: Remember which MAC addresses are on which ports of each switch.

**Type**: `Map<DeviceId, Map<MacAddress, PortNumber>>`

**Why nested maps?** 
- Outer map: One entry per switch (DeviceId)
- Inner map: For each switch, MAC → Port mappings

**Example scenario**:
```
Switch of:0000000000000001:
  ├─ 00:00:00:00:00:01 → Port 1
  ├─ 00:00:00:00:00:02 → Port 2
  └─ 00:00:00:00:00:03 → Port 3
```

**Thread safety**: Use `ConcurrentHashMap` because ONOS may call your methods from different threads.

### 2. Connection Tracking

**Purpose**: Track how many different destinations each source is talking to (for limiting).

**Type**: `Map<MacAddress, Set<MacAddress>>`

**Structure**:
- Key: Source MAC address
- Value: Set of destination MAC addresses

**Example**:
```
Source 00:00:00:00:00:01 → {00:00:00:00:00:02, 00:00:00:00:00:03}
                            ↑                                     ↑
                            2 active destinations (limit may be 2)
```

**Why a Set?** Sets automatically prevent duplicates and make checking membership efficient.

### 3. TCP Connection Tracking

**Purpose**: Store metadata about TCP connections for statistics.

**Type**: `Map<ConnectionKey, TcpConnectionInfo>`

**Key considerations**:
- Track connection start time (when SYN seen)
- Store device and endpoint information
- Calculate duration when connection ends

**Statistics to collect**:
- **Duration**: Connection lifetime in milliseconds
- **Bytes**: Total bytes transferred
- **Packets**: Total packet count

---

## Flow Rule Management

### Flow Lifecycle in OpenFlow

```
1. Packet arrives at switch
        ↓
2. No matching flow rule → Packet-In to controller
        ↓
3. Controller processes packet
        ↓
4. Controller installs flow rule in switch
        ↓
5. Future matching packets handled by switch (no controller)
        ↓
6. Flow times out or is removed
        ↓
7. FlowListener receives RULE_REMOVED event
        ↓
8. Controller cleans up tracking data
```

### TrafficSelector (Match Criteria)

Defines what packets should match this flow rule:

**Basic matching**:
- Input port
- Source/destination MAC addresses
- Ethernet type (IPv4, ARP, etc.)

**Advanced matching** (for TCP):
- IP protocol (TCP)
- Source/destination IP addresses
- Source/destination TCP ports

**Why specific matching for TCP?** To track individual TCP flows separately and collect per-flow statistics.

### TrafficTreatment (Actions)

Defines what to do with matching packets:

- Forward to specific port
- Forward to controller
- Drop
- Modify headers (not used in this app)

### ForwardingObjective

High-level abstraction combining selector and treatment:

**Properties**:
- **Priority**: Higher priority rules match first (use higher for TCP)
- **Timeout**: How long before rule expires (makeTemporary)
- **Permanent**: Rule never expires (makePermanent)
- **Flag**: VERSATILE for general forwarding

---

## Helper Classes

### ConnectionKey Class

**Purpose**: Uniquely identify a TCP connection (5-tuple).

**Required fields**:
- `MacAddress srcMac, dstMac` - Layer 2 endpoints
- `int srcIp, dstIp` - Layer 3 endpoints
- `int srcPort, dstPort` - Layer 4 endpoints

**Critical methods**:
- `equals()`: Two ConnectionKeys are equal if they represent the same connection
- `hashCode()`: Must be consistent with equals() for use in HashMap

**Why all these fields?** A TCP connection is uniquely identified by the 5-tuple (src IP, dst IP, src port, dst port, protocol).

### TcpConnectionInfo Class

**Purpose**: Store metadata for statistics collection.

**Required fields**:
- `DeviceId deviceId` - Which switch saw this connection
- `MacAddress srcMac, dstMac` - Endpoints for logging
- `long startTime` - When connection started (milliseconds)
- `long endTime` - When connection ended

**Key method**:
- `getDurationMs()`: Calculate connection duration
  - If ended: `endTime - startTime`
  - If active: `currentTime - startTime`

---

## ONOS API Examples

### Packet Processing APIs

**Get packet information**:
```java
InboundPacket pkt = context.inPacket();
Ethernet ethPkt = pkt.parsed();
DeviceId deviceId = pkt.receivedFrom().deviceId();
PortNumber inPort = pkt.receivedFrom().port();
```

**Extract packet headers**:
```java
MacAddress srcMac = ethPkt.getSourceMAC();
MacAddress dstMac = ethPkt.getDestinationMAC();

if (ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
    IPv4 ipv4 = (IPv4) ethPkt.getPayload();
    if (ipv4.getProtocol() == IPv4.PROTOCOL_TCP) {
        TCP tcp = (TCP) ipv4.getPayload();
        // Access TCP fields
    }
}
```

**Control packet**:
```java
context.send();           // Forward packet
context.block();          // Drop packet
context.isHandled();      // Check if already processed
```

### Flow Rule APIs

**Build a selector** (see ONOS FlowRule Tutorial for complete examples):
```java
TrafficSelector.Builder selector = DefaultTrafficSelector.builder()
    .matchInPort(portNumber)
    .matchEthSrc(srcMac)
    .matchEthDst(dstMac);
    // Add more match criteria as needed
```

**Build a treatment**:
```java
TrafficTreatment treatment = DefaultTrafficTreatment.builder()
    .setOutput(portNumber)
    .build();
```

**Create forwarding objective**:
```java
ForwardingObjective objective = DefaultForwardingObjective.builder()
    .withSelector(selector.build())
    .withTreatment(treatment)
    .withPriority(priority)          // Higher number = higher priority
    .withFlag(ForwardingObjective.Flag.VERSATILE)
    .fromApp(appId)
    .makeTemporary(timeoutSeconds)   // or .makePermanent()
    .add();
```

**Install flow rule**:
```java
flowObjectiveService.forward(deviceId, objective);
```

### Query Flow Rules

**Get all flows on a device**:
```java
Iterable<FlowEntry> flows = flowRuleService.getFlowEntries(deviceId);
for (FlowEntry flow : flows) {
    long bytes = flow.bytes();
    long packets = flow.packets();
    // Access flow statistics
}
```

**Extract criteria from flow**:
```java
TrafficSelector selector = flowRule.selector();
EthCriterion ethSrc = (EthCriterion) selector.getCriterion(Criterion.Type.ETH_SRC);
if (ethSrc != null) {
    MacAddress mac = ethSrc.mac();
}
```

### Device and Host APIs

**Get all switches**:
```java
Iterable<Device> devices = deviceService.getAvailableDevices();
```

**Check device type**:
```java
if (device.type() == Device.Type.SWITCH) {
    // It's a switch
}
```

---

## Testing Strategy

### Progressive Testing Approach

**Level 1: Basic Learning Bridge**
1. Implement MAC learning (Task 6)
2. Implement forwarding decision (Task 9)
3. Implement installRule (Task 10)
4. Test: `mininet> pingall` should work

**Level 2: Connection Limiting**
1. Implement connection tracking (Task 7)
2. Implement flow removal cleanup (Tasks 12, 14, 15)
3. Test: Third connection should be blocked

**Level 3: TCP Statistics**
1. Implement TCP tracking (Tasks 8, 11)
2. Implement TCP flow removal (Task 16)
3. Implement logging (Tasks 18, 19)
4. Test: Run iperf, check log file

### Validation Methods

**Check MAC learning**:
```bash
# In ONOS logs:
tail -f /opt/onos/apache-karaf-*/data/log/karaf.log | grep "Learned"
# Should see: Learned: MAC -> port X on device Y
```

**Check flow installation**:
```bash
# In ONOS CLI:
onos> flows -n
# Should see flow rules with your MAC addresses
```

**Check connection limiting**:
```bash
# ONOS logs should show:
"Connection limit reached for host X. Dropping packet to new destination Y"
```

**Check TCP statistics**:
```bash
# Check log file:
cat /tmp/tcp_connections.log
# Format: timestamp | SrcMAC | DstMAC | srcIP:port -> dstIP:port | Duration(ms) | Bytes | Packets
```

---

## Common Patterns and Best Practices

### Thread Safety

✅ **DO**: Use `ConcurrentHashMap`
```java
private Map<DeviceId, Map<MacAddress, PortNumber>> macTables = new ConcurrentHashMap<>();
```

❌ **DON'T**: Use `HashMap` (not thread-safe)

### Null Checking

✅ **DO**: Check before accessing
```java
Map<MacAddress, PortNumber> deviceTable = macTables.get(deviceId);
if (deviceTable != null) {
    PortNumber port = deviceTable.get(dstMac);
    if (port != null) {
        // Port is known
    }
}
```

### Initializing Nested Structures

✅ **DO**: Use `putIfAbsent`
```java
macTables.putIfAbsent(deviceId, new ConcurrentHashMap<>());
macTables.get(deviceId).put(srcMac, inPort);
```

### Broadcast/Multicast Handling

✅ **DO**: Exclude from connection limits
```java
if (!dstMac.isBroadcast() && !dstMac.isMulticast()) {
    // Apply connection limiting
}
```

**Why?** ARP, DHCP, and other essential protocols use broadcast/multicast.

---

## Reference Documentation

For detailed API documentation and examples:

- **ONOS Wiki**: https://wiki.onosproject.org/
  - Flow Rules: https://wiki.onosproject.org/display/ONOS/Flow+Rules
  - Packet Processing: https://wiki.onosproject.org/display/ONOS/Packet+Processing
  
- **ONOS Javadoc**: https://api.onosproject.org/

- **OpenFlow Tutorial**: Search for "ONOS reactive forwarding tutorial"

- **Sample Apps**: Look at `org.onosproject.fwd` (Reactive Forwarding) in ONOS source

---

## Troubleshooting Tips

**Application not starting**:
- Check `bundle:list | grep learning` for status
- Look for exceptions in `karaf.log`

**Packets not being processed**:
- Verify PacketProcessor was added
- Check that OpenFlow app is activated
- Look for "Packet received" in debug logs

**Flow rules not installing**:
- Verify device is connected (`devices` in CLI)
- Check flow installation code doesn't have errors
- Use `flows -n` to see if rules appear

**Statistics not logging**:
- Check file permissions on `/tmp`
- Verify TCP connections are being tracked
- Check flow timeout has elapsed

---

## Summary

This guide provides the architectural foundation you need to implement the learning bridge. Focus on:

1. Understanding the OSGi lifecycle and when methods are called
2. Choosing appropriate data structures for each task
3. Using ONOS APIs correctly for packet and flow management
4. Testing incrementally as you build

Refer to ONOS documentation for specific API examples, and build your implementation step by step!

Good luck! 🚀

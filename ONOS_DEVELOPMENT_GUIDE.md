# ONOS Learning Bridge – Development Guide

## Overview

This guide focuses on ONOS-specific development workflows, bundle management, and advanced debugging techniques for the Learning Bridge application.

**For initial setup**, see [GETTING_STARTED.md](GETTING_STARTED.md)  
**For architecture and concepts**, see [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md)  
**For quick commands**, see [QUICK_REFERENCE.md](QUICK_REFERENCE.md)

---

## Development Workflow

### Standard Iteration Cycle

```
Edit Code → Build → Update Bundle → Test → Monitor Logs
    ↑                                                ↓
    └────────────────────────────────────────────────┘
```

1. **Edit** code in `src/main/java/org/onosproject/learningbridge/LearningBridgeApp.java`
2. **Build**: `./build.sh`
3. **Update** bundle in ONOS CLI:
   ```text
   onos> bundle:update <ID> file:/workspaces/OnosSDNstudent/target/learning-bridge-1.0-SNAPSHOT.jar
   ```
4. **Test** in Mininet VM
5. **Monitor** logs for behavior

**Key principle**: Always use `bundle:update`, not uninstall/reinstall, to preserve state and speed up testing.

---

## Configuration Tuning

Edit these constants in `LearningBridgeApp.java` to modify behavior:

```java
// Connection limiting
private static final int MAX_CONNECTIONS_PER_HOST = 2;

// Flow timeout (in seconds)
private static final int FLOW_TIMEOUT = 30;

// TCP statistics log file
private static final String LOG_FILE_PATH = "/tmp/tcp_connections.log";

// Flow rule priority (higher = processed first)
private static final int PRIORITY = 2;
```

**After changing constants**: Rebuild and update the bundle.

---

## OSGi Bundle Management

### Understanding Bundle States

ONOS uses OSGi, which manages bundles through various states:

```
INSTALLED → RESOLVED → STARTING → ACTIVE → STOPPING → UNINSTALLED
```

**Key states**:
- **INSTALLED**: Bundle is installed but dependencies not resolved
- **RESOLVED**: Dependencies satisfied, ready to start
- **ACTIVE**: Bundle is running (@Activate called)
- **STOPPING**: Bundle is being stopped (@Deactivate called)

### Bundle Lifecycle Commands

```text
# View bundle details
onos> bundle:list | grep learning

# Start/stop bundle
onos> bundle:start <ID>
onos> bundle:stop <ID>

# Update bundle (preserves state)
onos> bundle:update <ID> file:/workspaces/OnosSDNstudent/target/learning-bridge-1.0-SNAPSHOT.jar

# Uninstall bundle
onos> bundle:uninstall <ID>

# Refresh bundle dependencies
onos> bundle:refresh <ID>
```

**Best practice**: Use `bundle:update` during development - it's faster and preserves application state.

### Component Lifecycle Hooks

Your app uses OSGi Declarative Services annotations:

```java
@Component(immediate = true)  // Start immediately when bundle activates
public class LearningBridgeApp {
    
    @Activate
    protected void activate() {
        // Called when component starts
        // Register listeners, initialize data structures
    }
    
    @Deactivate
    protected void deactivate() {
        // Called when component stops
        // Clean up listeners, remove flows, log statistics
    }
}
```

**Why this matters**: 
- @Activate runs AFTER all @Reference services are injected
- @Deactivate must clean up resources to prevent memory leaks
- Proper cleanup allows smooth bundle updates

---

## Advanced Debugging

### Logging Levels

Set logging granularity for your app:

```text
# Available levels: TRACE, DEBUG, INFO, WARN, ERROR
onos> log:set DEBUG org.onosproject.learningbridge
onos> log:set TRACE org.onosproject.learningbridge  # Very verbose
onos> log:set INFO org.onosproject.learningbridge   # Default
```

### Custom Logging in Code

```java
// Use SLF4J logger (already available in ONOS apps)
log.trace("Detailed trace: packet={}", packet);
log.debug("Debug info: srcMac={}, dstMac={}", srcMac, dstMac);
log.info("Information: Learned MAC {} on port {}", mac, port);
log.warn("Warning: Connection limit reached for {}", srcMac);
log.error("Error: Failed to install flow rule", exception);
```

**Best practices**:
- Use appropriate levels (don't log INFO for every packet)
- Include context in messages (MAC addresses, device IDs)
- Use placeholders `{}` for variables (more efficient)

### Debugging Flow Rules

```text
# View all flows
onos> flows

# View only app flows (no core flows)
onos> flows -n

# View flows for specific device
onos> flows of:0000000000000001

# View flow with statistics
onos> flows -s
```

**Interpreting flow output**:
```
id=..., state=ADDED, bytes=1024, packets=10, duration=5, priority=2
    selector=[IN_PORT:1, ETH_SRC:00:00:00:00:00:01, ETH_DST:00:00:00:00:00:02]
    treatment=[OUTPUT:2]
```

- **bytes/packets**: Statistics for this flow
- **duration**: How long the flow has been active (seconds)
- **selector**: What packets match this flow
- **treatment**: What action to take

### Monitoring Packet Processing

Enable packet-level debugging:

```java
// In LearningBridgeProcessor.process()
log.debug("Packet received: device={}, inPort={}, srcMac={}, dstMac={}", 
          deviceId, inPort, srcMac, dstMac);
```

Then monitor in real-time:
```bash
tail -f /opt/onos/apache-karaf-*/data/log/karaf.log | grep "Packet received"
```

### Debugging Connection Tracking

Add detailed logging:

```java
log.debug("Active destinations for {}: {}", srcMac, activeDestinations.get(srcMac));
log.warn("Connection limit reached: {} has {} active connections", 
         srcMac, activeDestinations.get(srcMac).size());
```

Monitor connection state:
```bash
tail -f /opt/onos/apache-karaf-*/data/log/karaf.log | grep -E "(Active destinations|Connection limit)"
```

---

## Common Development Tasks

### Task: Add Debug Logging

1. Add log statements in code:
   ```java
   log.debug("Processing packet from {} to {}", srcMac, dstMac);
   ```
2. Rebuild: `./build.sh`
3. Update bundle
4. Enable debug level:
   ```text
   onos> log:set DEBUG org.onosproject.learningbridge
   ```
5. Monitor: `tail -f /opt/onos/apache-karaf-*/data/log/karaf.log | grep LearningBridge`

### Task: Change Connection Limit

1. Edit constant:
   ```java
   private static final int MAX_CONNECTIONS_PER_HOST = 5;
   ```
2. Rebuild and update bundle
3. Test with xterm in Mininet

### Task: Change Flow Timeout

1. Edit constant:
   ```java
   private static final int FLOW_TIMEOUT = 60;  // 60 seconds
   ```
2. Rebuild and update bundle
3. Verify with: `onos> flows -s` (check duration values)

### Task: Add New Statistics

1. Add field to track data:
   ```java
   private AtomicLong totalPacketsProcessed = new AtomicLong(0);
   ```
2. Increment in packet processor:
   ```java
   totalPacketsProcessed.incrementAndGet();
   ```
3. Log in @Deactivate:
   ```java
   log.info("Total packets processed: {}", totalPacketsProcessed.get());
   ```

3. Log in @Deactivate:
   ```java
   log.info("Total packets processed: {}", totalPacketsProcessed.get());
   ```

### Task: Modify Packet Processing Logic

1. Edit `LearningBridgeProcessor.process()` method
2. Test logic with log statements before committing
3. Rebuild and update bundle
4. Verify in Mininet: `pingall` or custom tests
5. Monitor logs to confirm expected behavior

---

## Performance Considerations

### Flow Rule vs. Packet Processing

**Flow rule processing** (in switch):
- ✅ Fast - handled in hardware/switch firmware
- ✅ No controller overhead
- ✅ Scales to high packet rates

**Packet-in processing** (in controller):
- ⚠️ Slower - involves controller communication
- ⚠️ Limited by CPU and network bandwidth
- ⚠️ Only for first packet or unknown destinations

**Goal**: Install flow rules quickly to minimize packet-ins.

### When to Use makePermanent() vs makeTemporary()

```java
// Temporary flows (recommended for learning bridge)
.makeTemporary(FLOW_TIMEOUT)
```
- ✅ Automatically expires
- ✅ Frees resources
- ✅ Adapts to topology changes

```java
// Permanent flows
.makePermanent()
```
- ⚠️ Never expires automatically
- ⚠️ Must be manually removed
- ✅ Use for static forwarding rules

**This app uses temporary flows** to enable dynamic connection tracking and cleanup.

### Flow Rule Priority

```java
private static final int PRIORITY = 2;
```

**How priority works**:
- Higher number = higher priority
- Switch checks rules from highest to lowest priority
- First match wins

**In this app**:
- TCP flows: Higher priority (to track specifically)
- General flows: Normal priority
- Default: Priority 0

---

## Best Practices

### Code Organization

✅ **DO**:
- Keep packet processing logic in `LearningBridgeProcessor`
- Keep flow event handling in `InternalFlowListener`
- Use helper methods for complex operations
- Add meaningful log messages at key points

❌ **DON'T**:
- Mix concerns (keep MAC learning separate from connection limiting)
- Process packets synchronously for too long (blocks other packets)
- Forget to clean up in @Deactivate

### Thread Safety

✅ **DO**:
- Use `ConcurrentHashMap` for shared data structures
- Use `Collections.synchronizedMap()` if needed
- Be aware that ONOS may call methods from different threads

❌ **DON'T**:
- Use `HashMap` for shared state
- Assume single-threaded execution

### Error Handling

✅ **DO**:
```java
try {
    flowObjectiveService.forward(deviceId, objective);
} catch (Exception e) {
    log.error("Failed to install flow rule", e);
}
```

❌ **DON'T**:
- Let exceptions propagate uncaught
- Ignore error conditions silently

### Logging Hygiene

✅ **DO**:
- Log important state changes (INFO)
- Log errors with context (ERROR)
- Use DEBUG for detailed packet processing

❌ **DON'T**:
- Log every packet at INFO level (floods logs)
- Log sensitive data if this were production code

---

## Troubleshooting Development Issues

### Issue: Bundle Won't Start

**Symptoms**: Bundle shows INSTALLED but not ACTIVE

**Solutions**:
1. Check dependencies are resolved:
   ```text
   onos> bundle:list | grep learning
   ```
2. Check for exceptions:
   ```bash
   tail -f /opt/onos/apache-karaf-*/data/log/karaf.log | grep -i exception
   ```
3. Verify Java version compatibility (must be Java 11)

### Issue: Code Changes Not Reflected

**Symptoms**: Old behavior persists after bundle update

**Solutions**:
1. Verify build succeeded: Check for "BUILD SUCCESS" in `./build.sh` output
2. Use correct path in update command:
   ```text
   onos> bundle:update <ID> file:/workspaces/OnosSDNstudent/target/learning-bridge-1.0-SNAPSHOT.jar
   ```
3. Try stopping and starting:
   ```text
   onos> bundle:stop <ID>
   onos> bundle:start <ID>
   ```
4. Last resort - restart ONOS (loses all state):
   ```bash
   /opt/onos/bin/onos-service restart
   ```

### Issue: Flow Rules Not Installing

**Symptoms**: Packets keep arriving at controller, no flows in switch

**Check**:
1. OpenFlow connection:
   ```text
   onos> devices
   ```
   Should show switches as AVAILABLE

2. Flow objective service is working:
   ```text
   onos> flows -n
   ```
   Should show flows from your app

3. Add debug logging:
   ```java
   log.debug("Installing flow rule: {} -> {}", srcMac, dstMac);
   ```

4. Check for exceptions during flow installation

### Issue: Packet Processor Not Receiving Packets

**Symptoms**: No "Packet received" debug logs

**Check**:
1. Packet processor was added in @Activate
2. PacketService.requestPackets() was called
3. OpenFlow apps are active:
   ```text
   onos> app list | grep -E "(openflow|hostprovider)"
   ```

---

## Testing Strategies

### Unit Testing Approach

For student implementation, focus on:

1. **MAC Learning**: 
   - Test that MAC → port mappings are stored
   - Test lookup returns correct port
   - Test flooding when destination unknown

2. **Connection Limiting**:
   - Test counting active destinations
   - Test blocking when limit reached
   - Test allowing broadcast/multicast

3. **Flow Cleanup**:
   - Test destination removal when flows expire
   - Test query for remaining flows works correctly

### Integration Testing with Mininet

**Progressive testing**:

1. **Level 1**: Basic connectivity
   ```bash
   mininet> pingall  # All pings should work
   ```

2. **Level 2**: Connection limiting
   ```bash
   # Use xterm to open simultaneous connections
   # Verify 3rd connection is blocked
   ```

3. **Level 3**: TCP statistics
   ```bash
   # Run iperf tests
   # Verify statistics are logged
   ```

4. **Level 4**: Dynamic cleanup
   ```bash
   # Start 2 connections, stop 1, wait for timeout
   # Verify 3rd connection now allowed
   ```

---

## Further Reading

### Documentation Files
- [GETTING_STARTED.md](GETTING_STARTED.md) - Initial setup and environment configuration
- [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md) - Architecture and implementation concepts
- [QUICK_REFERENCE.md](QUICK_REFERENCE.md) - Command cheat sheet
- [README.md](README.md) - Project overview

### External Resources
- **ONOS Wiki**: https://wiki.onosproject.org/
  - Application development tutorials
  - API documentation
  - Best practices

- **ONOS JavaDoc**: https://api.onosproject.org/
  - Complete API reference
  - Service interfaces
  - Event types

- **OpenFlow 1.3 Spec**: https://www.opennetworking.org/
  - Understanding flow rules
  - Match fields
  - Actions

---

## Summary

This guide covered ONOS-specific development workflows:

✅ **Bundle Management**: How OSGi bundles work and lifecycle management  
✅ **Debugging**: Logging, flow inspection, and troubleshooting  
✅ **Common Tasks**: Configuration changes and feature additions  
✅ **Best Practices**: Thread safety, error handling, performance  
✅ **Testing**: Progressive testing strategy for validation  

**Remember**: 
- Use `bundle:update` for fast iterations
- Enable DEBUG logging during development
- Test incrementally (MAC learning → connection limiting → TCP stats)
- Monitor logs to understand behavior

Happy coding! 🚀

---

*Last updated: November 2025*


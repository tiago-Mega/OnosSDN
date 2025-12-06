package org.onosproject.learningbridge;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TCP;
import org.onlab.packet.TpPort;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleEvent;
import org.onosproject.net.flow.FlowRuleListener;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.EthCriterion;
import org.onosproject.net.flow.criteria.IPCriterion;
import org.onosproject.net.flow.criteria.TcpPortCriterion;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.TopologyService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ONOS Learning Bridge Application - Student Starter Template
 * 
 * LEARNING OBJECTIVES:
 * This application will teach you to implement a learning bridge with advanced features:
 * 1. Basic MAC address learning and forwarding
 * 2. Connection limiting (max simultaneous connections per host)
 * 3. TCP statistics logging (duration, bytes, packets)
 * 4. Flow rule management and lifecycle
 * 
 * CURRENT STATE: Acts like a HUB (floods all packets)
 * YOUR TASK: Implement learning bridge behavior with connection limiting and statistics
 * 
 * See IMPLEMENTATION_GUIDE.md for detailed guidance on architecture and data structures.
 */
@Component(immediate = true)
public class LearningBridgeApp {

    private final Logger log = LoggerFactory.getLogger(getClass());

    // ============================================================================
    // ONOS SERVICE REFERENCES (ALREADY PROVIDED)
    // ============================================================================
    // These @Reference annotations inject ONOS services into your application

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    // ============================================================================
    // APPLICATION STATE
    // ============================================================================
    
    private ApplicationId appId;
    private LearningBridgeProcessor processor = new LearningBridgeProcessor();
    private InternalFlowListener flowListener = new InternalFlowListener();

    // TODO: TASK 1 - Declare MAC Learning Table
    // HINT: Map<DeviceId, Map<MacAddress, PortNumber>> to store MAC->Port mappings per device
    // HINT: Use ConcurrentHashMap for thread-safety
    private Map<DeviceId, Map<MacAddress, PortNumber>> macTables = new ConcurrentHashMap<>();

    // TODO: TASK 2 - Declare Connection Tracking (for connection limiting)
    // HINT: Map<MacAddress, Set<MacAddress>> to track active destinations per source
    private Map<MacAddress, Set<MacAddress>> activeDestinations = new ConcurrentHashMap<>();

    // TODO: TASK 3 - Declare TCP Connection Tracking (for statistics)
    // HINT: Map<ConnectionKey, TcpConnectionInfo> to track TCP connections
    private Map<ConnectionKey, TcpConnectionInfo> tcpConnections = new ConcurrentHashMap<>();

    // TODO: TASK 4 - Define Configuration Constants
    private static final int MAX_CONNECTIONS_PER_HOST = 2;
    private static final int FLOW_TIMEOUT = 5;  // seconds
    private static final String LOG_FILE_PATH = "/tmp/tcp_connections.log";
    private static final int FLOW_PRIORITY = 100;
    private static final int TCP_FLOW_PRIORITY = 200;

    // ============================================================================
    // APPLICATION LIFECYCLE (ALREADY IMPLEMENTED)
    // ============================================================================

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("org.onosproject.learningbridge");
        packetService.addProcessor(processor, PacketProcessor.director(2));
        flowRuleService.addListener(flowListener);

        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);

        log.info("Learning Bridge Application Started (Author - Tiago Mega)");
    }

    @Deactivate
    protected void deactivate() {
        flowRuleService.removeListener(flowListener);
        packetService.removeProcessor(processor);
        flowRuleService.removeFlowRulesById(appId);

        // TODO: TASK 5 - Call logAllConnectionStats() if implementing TCP tracking
        logAllConnectionStats();

        log.info("Learning Bridge Application Stopped");
    }

    // ============================================================================
    // PACKET PROCESSING
    // ============================================================================

    private class LearningBridgeProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            // Extract packet information (ALREADY IMPLEMENTED)
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            if (ethPkt == null) {
                return;
            }

            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = ethPkt.getDestinationMAC();
            DeviceId deviceId = pkt.receivedFrom().deviceId();
            PortNumber inPort = pkt.receivedFrom().port();

            log.debug("Packet received: {} -> {} on device {} port {}", srcMac, dstMac, deviceId, inPort);

            // TODO: TASK 6 - Implement MAC Address Learning
            // HINT: Update macTables with srcMac -> inPort mapping for this deviceId
            macTables.putIfAbsent(deviceId, new ConcurrentHashMap<>());
            macTables.get(deviceId).put(srcMac, inPort);
            log.info("Learned: {} -> port {} on device {}", srcMac, inPort, deviceId);

            // TODO: TASK 7 - Implement Connection Limiting (ADVANCED)
            // HINT: Only for unicast (not broadcast/multicast)
            // HINT: Check if destination count exceeds MAX_CONNECTIONS_PER_HOST
            // HINT: If limit reached, block packet with context.block()
            if (!dstMac.isBroadcast() && !dstMac.isMulticast()) {
                if (!isConnectionAllowed(srcMac, dstMac)) {
                    context.block();  // Drop the packet
                    return;           // Don't process further
                }
            } else {
                log.debug("Skipping connection limit for broadcast/multicast: {} -> {}", srcMac, dstMac);
            }

            // TODO: TASK 8 - Handle TCP Packets (ADVANCED)
            // HINT: Check if packet is IPv4 and TCP protocol
            // HINT: Call handleTcpTracking() to track SYN packets
            boolean isTcp = false;
            if (ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
                IPv4 ipv4Packet = (IPv4) ethPkt.getPayload();
                if (ipv4Packet.getProtocol() == IPv4.PROTOCOL_TCP) {
                    isTcp = true;
                    handleTcpTracking(context, ethPkt, ipv4Packet);
                }
            }

            // TODO: TASK 9 - Implement Forwarding Decision
            // HINT: Look up dstMac in macTables.get(deviceId)
            // HINT: If found, call installRule(context, outPort, isTcp)
            // HINT: If not found, call flood(context)
            Map<MacAddress, PortNumber> deviceMacTable = macTables.get(deviceId);
            if (deviceMacTable != null && deviceMacTable.containsKey(dstMac)) {
                PortNumber outPort = deviceMacTable.get(dstMac);
                installRule(context, outPort, isTcp);
            } else {
                flood(context);
            }

            // Just flood (HUB behavior) -> flood(context);
        }

        /**
         * Floods packet to all ports (ALREADY IMPLEMENTED).
         * This makes the switch act like a HUB.
         */
        private void flood(PacketContext context) {
            context.treatmentBuilder().setOutput(PortNumber.FLOOD);
            context.send();
        }

        // TODO: TASK 10 - Implement installRule method
        private void installRule(PacketContext context, PortNumber portNumber, boolean isTcp) {
            Ethernet ethPkt = context.inPacket().parsed();
            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = ethPkt.getDestinationMAC();
            DeviceId deviceId = context.inPacket().receivedFrom().deviceId();
            PortNumber inPort = context.inPacket().receivedFrom().port();

            // Build TrafficSelector with MAC addresses, input port
            TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder()
                .matchInPort(inPort)
                .matchEthSrc(srcMac)
                .matchEthDst(dstMac)
            ;

            // For TCP: add IP addresses and ports
            if (isTcp && ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
                IPv4 ipv4Packet = (IPv4) ethPkt.getPayload();
                selectorBuilder.matchEthType(Ethernet.TYPE_IPV4)
                               .matchIPSrc(Ip4Prefix.valueOf(Ip4Address.valueOf(ipv4Packet.getSourceAddress()), 32))
                               .matchIPDst(Ip4Prefix.valueOf(Ip4Address.valueOf(ipv4Packet.getDestinationAddress()), 32));  
                
                if (ipv4Packet.getPayload() instanceof TCP) {
                    TCP tcpPacket = (TCP) ipv4Packet.getPayload();
                    selectorBuilder.matchIPProtocol(IPv4.PROTOCOL_TCP)
                                   .matchTcpSrc(TpPort.tpPort(tcpPacket.getSourcePort()))
                                   .matchTcpDst(TpPort.tpPort(tcpPacket.getDestinationPort()));
                }
            }

            // Build TrafficTreatment with output port
            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(portNumber)
                .build()
            ;
            
            // Create ForwardingObjective with priority and timeout
            int priority = isTcp ? TCP_FLOW_PRIORITY : FLOW_PRIORITY;
            ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selectorBuilder.build())
                .withTreatment(treatment)
                .withPriority(priority)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .makeTemporary(FLOW_TIMEOUT)
                .fromApp(appId)
                .add()
            ;

            // Install with flowObjectiveService.forward()
            try {
                flowObjectiveService.forward(deviceId, forwardingObjective);
            } catch (Exception e) {
                log.error("Failed to install flow rule", e);
            }

            // Update activeDestinations for connection limiting
            activeDestinations.putIfAbsent(srcMac, ConcurrentHashMap.newKeySet());
            activeDestinations.get(srcMac).add(dstMac);

            // Forward current packet
            context.treatmentBuilder().setOutput(portNumber);
            context.send();

            log.info("Installed flow rule on device {}: {} -> {} out port {} (TCP: {})", deviceId, srcMac, dstMac, portNumber, isTcp);
        }

        // TODO: TASK 11 - Implement handleTcpTracking method (ADVANCED)
        private void handleTcpTracking(PacketContext context, Ethernet ethPkt, IPv4 ipv4Packet) {
            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = ethPkt.getDestinationMAC();
            DeviceId deviceId = context.inPacket().receivedFrom().deviceId();
            Ip4Address srcIp = Ip4Address.valueOf(ipv4Packet.getSourceAddress());
            Ip4Address dstIp = Ip4Address.valueOf(ipv4Packet.getDestinationAddress());
            
            // Extract TCP packet, check for SYN flag
            TCP tcpPacket = (TCP) ipv4Packet.getPayload();
            int srcPort = tcpPacket.getSourcePort();
            int dstPort = tcpPacket.getDestinationPort();
            
            int flags = tcpPacket.getFlags(); 
            boolean isSyn = (flags & 0x02) != 0;

            if (isSyn) {                
                // Create ConnectionKey
                ConnectionKey connKey = new ConnectionKey(srcMac, dstMac, srcIp, dstIp, srcPort, dstPort);
                
                // Store in tcpConnections if new
                if(!tcpConnections.containsKey(connKey)) {
                    long startTimeMillis = System.currentTimeMillis();
                    tcpConnections.putIfAbsent(connKey, new TcpConnectionInfo(deviceId, srcMac, dstMac, startTimeMillis));
                    log.info("Tracking new TCP connection: {}:{} -> {}:{}", srcIp, srcPort, dstIp, dstPort);
                }
            }
        }

        // TODO: Helper method for connection limiting
        private boolean isConnectionAllowed(MacAddress srcMac, MacAddress dstMac) {
            Set<MacAddress> destinations = activeDestinations.get(srcMac);
    
            // No existing connections from srcMac
            if (destinations == null) {
                return true; 
            }
            
            // Already talking to this destination - allow (existing connection)
            if (destinations.contains(dstMac)) {
                return true;
            }
            
            // Check if at limit
            if (destinations.size() >= MAX_CONNECTIONS_PER_HOST) {
                return false;  // Reject: at capacity for new destination
            }
            
            return true;  // Allow: have capacity for new destination
        }
    }

    // ============================================================================
    // FLOW RULE MANAGEMENT
    // ============================================================================

    private class InternalFlowListener implements FlowRuleListener {
        
        @Override
        public void event(FlowRuleEvent event) {
            FlowRule flowRule = event.subject();

            if (flowRule.appId() != appId.id()) {
                return;
            }

            if (event.type() == FlowRuleEvent.Type.RULE_REMOVED) {
                log.debug("Flow rule removed: {}", flowRule.id());
                
                // TODO: TASK 12 - Call handleFlowRemoval(flowRule)
                handleFlowRemoval(flowRule);

                // TODO: TASK 13 - Call handleTcpFlowRemoval(flowRule) for TCP flows
                handleTcpFlowRemoval(flowRule);
            }
        }

        // TODO: TASK 14 - Implement handleFlowRemoval method
        private void handleFlowRemoval(FlowRule flowRule) {
            TrafficSelector selector = flowRule.selector();

            // Extract src and dst MAC from flow rule
            EthCriterion srcEthCriterion = (EthCriterion) selector.getCriterion(Criterion.Type.ETH_SRC);
            EthCriterion dstEthCriterion = (EthCriterion) selector.getCriterion(Criterion.Type.ETH_DST);

            if (srcEthCriterion == null || dstEthCriterion == null) {
                return; // Not a MAC-based flow
            }

            MacAddress srcMac = srcEthCriterion.mac();
            MacAddress dstMac = dstEthCriterion.mac();
            
            // Check if any other flows exist between them
            if (!hasActiveFlowsBetween(srcMac, dstMac)) {
                // Check if any active TCP connections exist between them
                if (!hasActiveConnectionsTo(srcMac, dstMac)) {  
                    // Remove from activeDestinations
                    Set<MacAddress> destinations = activeDestinations.get(srcMac);
                    if (destinations != null) {
                        destinations.remove(dstMac);
                        log.info("Removed active destination {} for source {}", dstMac, srcMac);
                    }
                } else {
                    log.debug("TCP connections still active between {} and {}, keeping destination", srcMac, dstMac);
                }
            }
        }

        // TODO: TASK 15 - Implement hasActiveFlowsBetween method
        private boolean hasActiveFlowsBetween(MacAddress srcMac, MacAddress dstMac) {
            // Query all devices and their flow entries
            for (Device device : deviceService.getAvailableDevices()) {
                if (device.type() == Device.Type.SWITCH) {
                    Iterable<FlowEntry> flowsEntry = flowRuleService.getFlowEntries(device.id());
                    for (FlowEntry flowEntry : flowsEntry) {
                        if (flowEntry.appId() == appId.id()) {
                            TrafficSelector selector = flowEntry.selector();
                            EthCriterion srcEthCriterion = (EthCriterion) selector.getCriterion(Criterion.Type.ETH_SRC);
                            EthCriterion dstEthCriterion = (EthCriterion) selector.getCriterion(Criterion.Type.ETH_DST);

                            if (srcEthCriterion != null && dstEthCriterion != null) {
                                MacAddress flowSrcMac = srcEthCriterion.mac();
                                MacAddress flowDstMac = dstEthCriterion.mac();

                                if (flowSrcMac.equals(srcMac) && flowDstMac.equals(dstMac)) {
                                    // Return true if any flow matches srcMac -> dstMac            
                                    return true; 
                                }
                            }
                        }
                    }            
                }
            }

            return false; // No active flows found between srcMac -> dstMac
        }

        // TODO: TASK 16 - Implement handleTcpFlowRemoval method (ADVANCED)
        private void handleTcpFlowRemoval(FlowRule flowRule) {
            TrafficSelector selector = flowRule.selector();
            long bytes = 0;
            long packets = 0;

            // Check if TCP flow
            TcpPortCriterion srcTcpCriterion = (TcpPortCriterion) selector.getCriterion(Criterion.Type.TCP_SRC);
            TcpPortCriterion dstTcpCriterion = (TcpPortCriterion) selector.getCriterion(Criterion.Type.TCP_DST);

            if (srcTcpCriterion == null || dstTcpCriterion == null) {
                return; // Not a TCP flow
            }

            // Extract connection details
            EthCriterion srcEthCriterion = (EthCriterion) selector.getCriterion(Criterion.Type.ETH_SRC);
            EthCriterion dstEthCriterion = (EthCriterion) selector.getCriterion(Criterion.Type.ETH_DST);
            IPCriterion srcIpCriterion = (IPCriterion) selector.getCriterion(Criterion.Type.IPV4_SRC);
            IPCriterion dstIpCriterion = (IPCriterion) selector.getCriterion(Criterion.Type.IPV4_DST);

            // Ensure all criteria are present
            if (srcEthCriterion == null || dstEthCriterion == null || srcIpCriterion == null || dstIpCriterion == null) {
                return; 
            }

            // Get statistics from FlowEntry (bytes(), packets())
            if (flowRule instanceof FlowEntry) {
                FlowEntry flowEntry = (FlowEntry) flowRule;
                bytes = flowEntry.bytes();
                packets = flowEntry.packets();
                log.info("Got stats from FlowEntry: {} bytes, {} packets", bytes, packets);
            } else {
                log.warn("FlowRule is not FlowEntry, cannot get stats");
            }

            // Create ConnectionKey
            ConnectionKey logKey = new ConnectionKey(
                srcEthCriterion.mac(),
                dstEthCriterion.mac(),
                srcIpCriterion.ip().getIp4Prefix().address(),
                dstIpCriterion.ip().getIp4Prefix().address(),
                srcTcpCriterion.tcpPort().toInt(),
                dstTcpCriterion.tcpPort().toInt()
            );  
            
            // Remove from tcpConnections and set end time
            TcpConnectionInfo info = tcpConnections.remove(logKey);
            if (info != null) {
                info.setEndTime();
            }

            // Log connection statistics
            logTcpConnectionStats(logKey, info, bytes, packets); 
        }

        // TODO: TASK 17 - Implement hasActiveConnectionsTo method (ADVANCED)
        private boolean hasActiveConnectionsTo(MacAddress srcMac, MacAddress dstMac) {
            // Check tcpConnections map for matching entries
            for (Map.Entry<ConnectionKey, TcpConnectionInfo> entry : tcpConnections.entrySet()) {
                ConnectionKey key = entry.getKey();
                if (key.srcMac.equals(srcMac) && key.dstMac.equals(dstMac)) {
                    return true; // Active TCP connection exists
                }
            }
            return false;
        }
    }

    // ============================================================================
    // STATISTICS AND LOGGING (ADVANCED TASKS)
    // ============================================================================

    // TODO: TASK 18 - Implement logTcpConnectionStats method
    private void logTcpConnectionStats(ConnectionKey connKey, TcpConnectionInfo info, long bytes, long packets) {

        // Format: timestamp | SrcMAC | DstMAC | srcIP:srcPort -> dstIP:dstPort | Duration(ms) | Bytes | Packets
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        String logEntry = String.format("%s | %s | %s | %s | %s | %d | %d | %d",
                timestamp,
                info.deviceId,
                info.srcMac,
                info.dstMac,
                connKey.toString(),  // "srcIP:srcPort -> dstIP:dstPort"
                info.getDurationMillis(),
                bytes,
                packets
        );

        // Write to LOG_FILE_PATH
        try (FileWriter fw = new FileWriter(LOG_FILE_PATH, true); PrintWriter pw = new PrintWriter(fw)) {
            pw.println(logEntry);
            log.info("TCP connection logged: {}", logEntry);
        } 
        catch (IOException e) {
            log.error("Error writing TCP connection stats to log file", e);
        }

    }

    // TODO: TASK 19 - Implement logAllConnectionStats method
    private void logAllConnectionStats() {

        // Iterate through tcpConnections and log stats for each
        for (Map.Entry<ConnectionKey, TcpConnectionInfo> entry : tcpConnections.entrySet()) {
            ConnectionKey connKey = entry.getKey();
            TcpConnectionInfo info = entry.getValue();

            // For simplicity, log with zero bytes/packets (on shutdown)
            logTcpConnectionStats(connKey, info, 0, 0);
        }
    }

    // ============================================================================
    // HELPER CLASSES
    // ============================================================================

    // TODO: TASK 20 - Implement ConnectionKey class
    // See IMPLEMENTATION_GUIDE.md for structure and purpose
    //
    private static class ConnectionKey {
        
        // Fields to uniquely identify TCP connection
        private final MacAddress srcMac;
        private final MacAddress dstMac;
        private final Ip4Address srcIp;
        private final Ip4Address dstIp;
        private final int srcPort;
        private final int dstPort;
        
        public ConnectionKey(MacAddress srcMac, MacAddress dstMac, Ip4Address srcIp, Ip4Address dstIp, int srcPort, int dstPort) {
            this.srcMac = srcMac;
            this.dstMac = dstMac;
            this.srcIp = srcIp;
            this.dstIp = dstIp;
            this.srcPort = srcPort;
            this.dstPort = dstPort;
        }

        // Implement equals() and hashCode()
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ConnectionKey)) return false;

            ConnectionKey other = (ConnectionKey) obj;
            return 
                srcPort == other.srcPort &&
                dstPort == other.dstPort &&
                java.util.Objects.equals(srcMac, other.srcMac) &&
                java.util.Objects.equals(dstMac, other.dstMac) &&
                java.util.Objects.equals(srcIp, other.srcIp) &&
                java.util.Objects.equals(dstIp, other.dstIp)
            ;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(srcMac, dstMac, srcIp, dstIp, srcPort, dstPort);
        }

        @Override
        public String toString() {
            return String.format("%s:%d -> %s:%d", srcIp, srcPort, dstIp, dstPort);
        }
    }

    // TODO: TASK 21 - Implement TcpConnectionInfo class
    // See IMPLEMENTATION_GUIDE.md for structure and purpose
    //
    private static class TcpConnectionInfo {

        // Fields to store connection metadata
        private final DeviceId deviceId;
        private final MacAddress srcMac;
        private final MacAddress dstMac;    
        private final long startTimeMillis;
        private long endTimeMillis = -1; // Initialized to -1 (set on removal)

        public TcpConnectionInfo(DeviceId deviceId, MacAddress srcMac, MacAddress dstMac, long startTimeMillis) {
            this.deviceId = deviceId;
            this.srcMac = srcMac;
            this.dstMac = dstMac;
            this.startTimeMillis = startTimeMillis;
        }
    

        public void setEndTime() {
            this.endTimeMillis = System.currentTimeMillis();
        }
        
        // Method to calculate duration
        public long getDurationMillis() {
            if (endTimeMillis == -1) {
                return System.currentTimeMillis() - startTimeMillis;
            }
            return endTimeMillis - startTimeMillis;
        }

    }

}

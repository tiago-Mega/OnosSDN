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
    // private Map<DeviceId, Map<MacAddress, PortNumber>> macTables = new ConcurrentHashMap<>();

    // TODO: TASK 2 - Declare Connection Tracking (for connection limiting)
    // HINT: Map<MacAddress, Set<MacAddress>> to track active destinations per source
    // private Map<MacAddress, Set<MacAddress>> activeDestinations = new ConcurrentHashMap<>();

    // TODO: TASK 3 - Declare TCP Connection Tracking (for statistics)
    // HINT: Map<ConnectionKey, TcpConnectionInfo> to track TCP connections
    // private Map<ConnectionKey, TcpConnectionInfo> tcpConnections = new ConcurrentHashMap<>();

    // TODO: TASK 4 - Define Configuration Constants
    // private static final int MAX_CONNECTIONS_PER_HOST = 2;
    // private static final int FLOW_TIMEOUT = 5;  // seconds
    // private static final String LOG_FILE_PATH = "/tmp/tcp_connections.log";

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

        log.info("Learning Bridge Application Started (Student Version - Hub Mode)");
    }

    @Deactivate
    protected void deactivate() {
        flowRuleService.removeListener(flowListener);
        packetService.removeProcessor(processor);
        flowRuleService.removeFlowRulesById(appId);

        // TODO: TASK 5 - Call logAllConnectionStats() if implementing TCP tracking

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

            log.debug("Packet received: {} -> {} on device {} port {}", 
                      srcMac, dstMac, deviceId, inPort);

            // TODO: TASK 6 - Implement MAC Address Learning
            // HINT: Update macTables with srcMac -> inPort mapping for this deviceId
            // macTables.putIfAbsent(deviceId, new ConcurrentHashMap<>());
            // macTables.get(deviceId).put(srcMac, inPort);
            // log.info("Learned: {} -> port {} on device {}", srcMac, inPort, deviceId);

            // TODO: TASK 7 - Implement Connection Limiting (ADVANCED)
            // HINT: Only for unicast (not broadcast/multicast)
            // HINT: Check if destination count exceeds MAX_CONNECTIONS_PER_HOST
            // HINT: If limit reached, block packet with context.block()
            // if (!dstMac.isBroadcast() && !dstMac.isMulticast()) {
            //     // Track and enforce connection limit  
            // }

            // TODO: TASK 8 - Handle TCP Packets (ADVANCED)
            // HINT: Check if packet is IPv4 and TCP protocol
            // HINT: Call handleTcpTracking() to track SYN packets
            // boolean isTcp = false;
            // if (ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
            //     IPv4 ipv4Packet = (IPv4) ethPkt.getPayload();
            //     if (ipv4Packet.getProtocol() == IPv4.PROTOCOL_TCP) {
            //         isTcp = true;
            //         handleTcpTracking(context, ethPkt, ipv4Packet);
            //     }
            // }

            // TODO: TASK 9 - Implement Forwarding Decision
            // HINT: Look up dstMac in macTables.get(deviceId)
            // HINT: If found, call installRule(context, outPort, isTcp)
            // HINT: If not found, call flood(context)
            
            // CURRENT IMPLEMENTATION: Just flood (HUB behavior)
            flood(context);
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
        // private void installRule(PacketContext context, PortNumber portNumber, boolean isTcp) {
        //     // Build TrafficSelector with MAC addresses, input port
        //     // For TCP: add IP addresses and ports
        //     // Build TrafficTreatment with output port
        //     // Create ForwardingObjective with priority and timeout
        //     // Install with flowObjectiveService.forward()
        //     // Forward current packet
        // }

        // TODO: TASK 11 - Implement handleTcpTracking method (ADVANCED)
        // private void handleTcpTracking(PacketContext context, Ethernet ethPkt, IPv4 ipv4Packet) {
        //     // Extract TCP packet, check for SYN flag
        //     // Create ConnectionKey
        //     // Store in tcpConnections if new
        // }
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
                // TODO: TASK 13 - Call handleTcpFlowRemoval(flowRule) for TCP flows
            }
        }

        // TODO: TASK 14 - Implement handleFlowRemoval method
        // private void handleFlowRemoval(FlowRule flowRule) {
        //     // Extract src and dst MAC from flow rule
        //     // Check if any other flows exist between them
        //     // If not, remove from activeDestinations
        // }

        // TODO: TASK 15 - Implement hasActiveFlowsBetween method
        // private boolean hasActiveFlowsBetween(MacAddress srcMac, MacAddress dstMac) {
        //     // Query all devices and their flow entries
        //     // Return true if any flow matches srcMac -> dstMac
        // }

        // TODO: TASK 16 - Implement handleTcpFlowRemoval method (ADVANCED)
        // private void handleTcpFlowRemoval(FlowRule flowRule) {
        //     // Check if TCP flow
        //     // Extract connection details
        //     // Get statistics from FlowEntry (bytes(), packets())
        //     // Log to file with duration, bytes, packets
        // }

        // TODO: TASK 17 - Implement hasActiveConnectionsTo method (ADVANCED)
        // private boolean hasActiveConnectionsTo(MacAddress srcMac, MacAddress dstMac) {
        //     // Check tcpConnections map for matching entries
        // }
    }

    // ============================================================================
    // STATISTICS AND LOGGING (ADVANCED TASKS)
    // ============================================================================

    // TODO: TASK 18 - Implement logTcpConnectionStats method
    // private void logTcpConnectionStats(ConnectionKey connKey, TcpConnectionInfo info, 
    //                                    long bytes, long packets) {
    //     // Format: timestamp | SrcMAC | DstMAC | srcIP:srcPort -> dstIP:dstPort | Duration(ms) | Bytes | Packets
    //     // Write to LOG_FILE_PATH
    // }

    // TODO: TASK 19 - Implement logAllConnectionStats method
    // private void logAllConnectionStats() {
    //     // Iterate through tcpConnections and log stats for each
    // }

    // ============================================================================
    // HELPER CLASSES
    // ============================================================================

    // TODO: TASK 20 - Implement ConnectionKey class
    // See IMPLEMENTATION_GUIDE.md for structure and purpose
    /*
    private static class ConnectionKey {
        // Fields to uniquely identify TCP connection
        // Implement equals() and hashCode()
    }
    */

    // TODO: TASK 21 - Implement TcpConnectionInfo class
    // See IMPLEMENTATION_GUIDE.md for structure and purpose
    /*
    private static class TcpConnectionInfo {
        // Fields to store connection metadata
        // Method to calculate duration
    }
    */
}

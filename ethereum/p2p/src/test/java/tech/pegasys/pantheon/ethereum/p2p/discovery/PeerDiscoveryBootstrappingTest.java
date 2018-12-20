/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.p2p.discovery;

import static io.vertx.core.Vertx.vertx;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.vertx.core.datagram.DatagramSocket;
import tech.pegasys.pantheon.crypto.SECP256K1;
import tech.pegasys.pantheon.ethereum.p2p.config.DiscoveryConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.config.PermissioningConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.Packet;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PacketType;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PingPacketData;
import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.ethereum.p2p.permissioning.NodeWhitelistController;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.vertx.core.Vertx;
import org.junit.Test;

public class PeerDiscoveryBootstrappingTest extends AbstractPeerDiscoveryTest {

    @Test
    public void bootstrappingPingsSentSingleBootstrapPeer() throws Exception {
        // Start one test peer and use it as a bootstrap peer.
        final DiscoveryTestSocket discoveryTestSocket = startTestSocket();
        final List<DiscoveryPeer> bootstrapPeers = singletonList(discoveryTestSocket.getPeer());

        // Start an agent.
        final PeerDiscoveryAgent agent = startDiscoveryAgent(bootstrapPeers);

        final Packet packet = discoveryTestSocket.getIncomingPackets().poll(2, TimeUnit.SECONDS);

        assertThat(packet.getType()).isEqualTo(PacketType.PING);
        assertThat(packet.getNodeId()).isEqualTo(agent.getAdvertisedPeer().getId());

        final PingPacketData pingData = packet.getPacketData(PingPacketData.class).get();
        assertThat(pingData.getExpiration())
                .isGreaterThanOrEqualTo(System.currentTimeMillis() / 1000 - 10000);
        assertThat(pingData.getFrom()).isEqualTo(agent.getAdvertisedPeer().getEndpoint());
        assertThat(pingData.getTo()).isEqualTo(discoveryTestSocket.getPeer().getEndpoint());
    }

    @Test
    public void bootstrappingPingsSentMultipleBootstrapPeers() {
        // Start three test peers.
        startTestSockets(3);

        // Use these peers as bootstrap peers.
        final List<DiscoveryPeer> bootstrapPeers =
                discoveryTestSockets.stream().map(DiscoveryTestSocket::getPeer).collect(toList());

        // Start five agents.
        startDiscoveryAgents(5, bootstrapPeers);

        // Assert that all test peers received a Find Neighbors packet.
        for (final DiscoveryTestSocket peer : discoveryTestSockets) {
            // Five messages per peer (sent by each of the five agents).
            final List<Packet> packets = Stream.generate(peer::compulsoryPoll).limit(5).collect(toList());

            // No more messages left.
            assertThat(peer.getIncomingPackets().size()).isEqualTo(0);

            // Assert that the node IDs we received belong to the test agents.
            final List<BytesValue> peerIds = packets.stream().map(Packet::getNodeId).collect(toList());
            final List<BytesValue> nodeIds =
                    agents
                            .stream()
                            .map(PeerDiscoveryAgent::getAdvertisedPeer)
                            .map(Peer::getId)
                            .collect(toList());

            assertThat(peerIds).containsExactlyInAnyOrderElementsOf(nodeIds);

            // Traverse all received packets.
            for (final Packet packet : packets) {
                // Assert that the packet was a Find Neighbors one.
                assertThat(packet.getType()).isEqualTo(PacketType.PING);

                // Assert on the content of the packet data.
                final PingPacketData ping = packet.getPacketData(PingPacketData.class).get();
                assertThat(ping.getExpiration())
                        .isGreaterThanOrEqualTo(System.currentTimeMillis() / 1000 - 10000);
                assertThat(ping.getTo()).isEqualTo(peer.getPeer().getEndpoint());
            }
        }
    }

    @Test
    public void deconstructedBootstrappingPingsSentMultipleBootstrapPeers() throws Exception {
        final Vertx vertx = vertx();
        final String LOOPBACK_IP_ADDR = "127.0.0.1";
        final int TEST_SOCKET_START_TIMEOUT_SECS = 5;
        final int BROADCAST_TCP_PORT = 12356;

        final List<DiscoveryTestSocket> discoveryTestSocketList = new CopyOnWriteArrayList<>();
        //List<PeerDiscoveryAgent> peerDiscoveryAgentList = new CopyOnWriteArrayList<>();

        // Start three test peers.

        /* * */
        final ArrayBlockingQueue<Packet> arrayBlockingQueue0 = new ArrayBlockingQueue<>(100);
        final SECP256K1.KeyPair keyPair0 = SECP256K1.KeyPair.generate();
        final BytesValue peerId0 = keyPair0.getPublicKey().getEncodedBytes();
        final CompletableFuture<DiscoveryTestSocket> result0 = new CompletableFuture<>();
        // Test packet handler which feeds the received packet into a Future we later consume from.
        vertx.createDatagramSocket().listen(
                0,
                LOOPBACK_IP_ADDR,
                ar -> {
                    if (!ar.succeeded()) {
                        result0.completeExceptionally(ar.cause());
                        return;
                    }
                    final DatagramSocket socket = ar.result();
                    socket.handler(p -> arrayBlockingQueue0.add(Packet.decode(p.data())));
                    final DiscoveryPeer peer = new DiscoveryPeer(peerId0, LOOPBACK_IP_ADDR, socket.localAddress().port(), socket.localAddress().port());
                    final DiscoveryTestSocket discoveryTestSocket = new DiscoveryTestSocket(peer, keyPair0, arrayBlockingQueue0, socket);
                    result0.complete(discoveryTestSocket);
                });
        final DiscoveryTestSocket discoveryTestSocket0 = result0.get(TEST_SOCKET_START_TIMEOUT_SECS, TimeUnit.SECONDS);
        discoveryTestSocketList.add(discoveryTestSocket0);
        final DiscoveryPeer discoveryPeer0 = discoveryTestSocket0.getPeer();
        /* * */
        final ArrayBlockingQueue<Packet> arrayBlockingQueue1 = new ArrayBlockingQueue<>(100);
        final SECP256K1.KeyPair keyPair1 = SECP256K1.KeyPair.generate();
        final BytesValue peerId1 = keyPair1.getPublicKey().getEncodedBytes();
        final CompletableFuture<DiscoveryTestSocket> result1 = new CompletableFuture<>();
        // Test packet handler which feeds the received packet into a Future we later consume from.
        vertx.createDatagramSocket().listen(
                0,
                LOOPBACK_IP_ADDR,
                ar -> {
                    if (!ar.succeeded()) {
                        result1.completeExceptionally(ar.cause());
                        return;
                    }
                    final DatagramSocket socket = ar.result();
                    socket.handler(p -> arrayBlockingQueue1.add(Packet.decode(p.data())));
                    final DiscoveryPeer peer = new DiscoveryPeer(peerId1, LOOPBACK_IP_ADDR, socket.localAddress().port(), socket.localAddress().port());
                    final DiscoveryTestSocket discoveryTestSocket = new DiscoveryTestSocket(peer, keyPair1, arrayBlockingQueue1, socket);
                    result1.complete(discoveryTestSocket);
                });
        final DiscoveryTestSocket discoveryTestSocket1 = result1.get(TEST_SOCKET_START_TIMEOUT_SECS, TimeUnit.SECONDS);
        discoveryTestSocketList.add(discoveryTestSocket1);
        final DiscoveryPeer discoveryPeer1 = discoveryTestSocket1.getPeer();
        /* * */
        final ArrayBlockingQueue<Packet> arrayBlockingQueue2 = new ArrayBlockingQueue<>(100);
        final SECP256K1.KeyPair keyPair2 = SECP256K1.KeyPair.generate();
        final BytesValue peerId2 = keyPair2.getPublicKey().getEncodedBytes();
        final CompletableFuture<DiscoveryTestSocket> result2 = new CompletableFuture<>();
        // Test packet handler which feeds the received packet into a Future we later consume from.
        vertx.createDatagramSocket().listen(
                0,
                LOOPBACK_IP_ADDR,
                ar -> {
                    if (!ar.succeeded()) {
                        result2.completeExceptionally(ar.cause());
                        return;
                    }
                    final DatagramSocket socket = ar.result();
                    socket.handler(p -> arrayBlockingQueue2.add(Packet.decode(p.data())));
                    final DiscoveryPeer peer = new DiscoveryPeer(peerId2, LOOPBACK_IP_ADDR, socket.localAddress().port(), socket.localAddress().port());
                    final DiscoveryTestSocket discoveryTestSocket = new DiscoveryTestSocket(peer, keyPair2, arrayBlockingQueue2, socket);
                    result2.complete(discoveryTestSocket);
                });
        final DiscoveryTestSocket discoveryTestSocket2 = result2.get(TEST_SOCKET_START_TIMEOUT_SECS, TimeUnit.SECONDS);
        discoveryTestSocketList.add(discoveryTestSocket2);
        final DiscoveryPeer discoveryPeer2 = discoveryTestSocket2.getPeer();


        final List<DiscoveryPeer> discoveryPeerList = Arrays.asList(discoveryPeer0, discoveryPeer1, discoveryPeer2);


        // Start five agents.
        //startDiscoveryAgents(5, bootstrapPeers);


        final DiscoveryConfiguration discoveryConfiguration0 = new DiscoveryConfiguration();
        discoveryConfiguration0.setBootstrapPeers(discoveryPeerList);
        discoveryConfiguration0.setBindPort(0);
        final PeerDiscoveryAgent peerDiscoveryAgent0 =
                new PeerDiscoveryAgent(
                        vertx,
                        SECP256K1.KeyPair.generate(),
                        discoveryConfiguration0,
                        () -> true,
                        new PeerBlacklist(),
                        new NodeWhitelistController(PermissioningConfiguration.createDefault()));
        peerDiscoveryAgent0.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);

        final DiscoveryConfiguration discoveryConfiguration1 = new DiscoveryConfiguration();
        discoveryConfiguration1.setBootstrapPeers(discoveryPeerList);
        discoveryConfiguration1.setBindPort(0);
        final PeerDiscoveryAgent peerDiscoveryAgent1 =
                new PeerDiscoveryAgent(
                        vertx,
                        SECP256K1.KeyPair.generate(),
                        discoveryConfiguration1,
                        () -> true,
                        new PeerBlacklist(),
                        new NodeWhitelistController(PermissioningConfiguration.createDefault()));
        peerDiscoveryAgent1.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);

        final DiscoveryConfiguration discoveryConfiguration2 = new DiscoveryConfiguration();
        discoveryConfiguration2.setBootstrapPeers(discoveryPeerList);
        discoveryConfiguration2.setBindPort(0);
        final PeerDiscoveryAgent peerDiscoveryAgent2 =
                new PeerDiscoveryAgent(
                        vertx,
                        SECP256K1.KeyPair.generate(),
                        discoveryConfiguration2,
                        () -> true,
                        new PeerBlacklist(),
                        new NodeWhitelistController(PermissioningConfiguration.createDefault()));
        peerDiscoveryAgent2.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);

        final DiscoveryConfiguration discoveryConfiguration3 = new DiscoveryConfiguration();
        discoveryConfiguration3.setBootstrapPeers(discoveryPeerList);
        discoveryConfiguration3.setBindPort(0);
        final PeerDiscoveryAgent peerDiscoveryAgent3 =
                new PeerDiscoveryAgent(
                        vertx,
                        SECP256K1.KeyPair.generate(),
                        discoveryConfiguration3,
                        () -> true,
                        new PeerBlacklist(),
                        new NodeWhitelistController(PermissioningConfiguration.createDefault()));
        peerDiscoveryAgent3.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);

        final DiscoveryConfiguration discoveryConfiguration4 = new DiscoveryConfiguration();
        discoveryConfiguration4.setBootstrapPeers(discoveryPeerList);
        discoveryConfiguration4.setBindPort(0);
        final PeerDiscoveryAgent peerDiscoveryAgent4 =
                new PeerDiscoveryAgent(
                        vertx,
                        SECP256K1.KeyPair.generate(),
                        discoveryConfiguration4,
                        () -> true,
                        new PeerBlacklist(),
                        new NodeWhitelistController(PermissioningConfiguration.createDefault()));
        peerDiscoveryAgent4.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);



        final BytesValue id0 = peerDiscoveryAgent0.getAdvertisedPeer().getId();
        final BytesValue id1 = peerDiscoveryAgent1.getAdvertisedPeer().getId();
        final BytesValue id2 = peerDiscoveryAgent2.getAdvertisedPeer().getId();
        final BytesValue id3 = peerDiscoveryAgent3.getAdvertisedPeer().getId();
        final BytesValue id4 = peerDiscoveryAgent4.getAdvertisedPeer().getId();
        final List<BytesValue> peerDiscoveryAgentIdList = Arrays.asList(id0, id1, id2, id3, id4);



        final List<Packet> packetList0 = Stream.generate(discoveryTestSocket0::compulsoryPoll).limit(arrayBlockingQueue0.size()).collect(toList());
        int count0 = 0;
        final List<BytesValue> idList0 = new ArrayList<>();
        for (Packet packet : packetList0) {
            //System.out.println("packet" + count0 + ": " + packet);
            idList0.add(packet.getNodeId());
            count0++;
        }
        //System.out.println(discoveryTestSocket0.getIncomingPackets().size());



        int countA = 0;
        for (BytesValue id : idList0) {
            System.out.println("* id" + countA + ": " + id);
            countA++;
        }
        System.out.println("---");
        int countB = 0;
        for (BytesValue id : peerDiscoveryAgentIdList) {
            System.out.println("~ id" + countB + ": " + id);
            countB++;
        }



        System.out.println("---");


        final List<Packet> packetList1 = Stream.generate(discoveryTestSocket1::compulsoryPoll).limit(arrayBlockingQueue1.size()).collect(toList());
        int count1 = 0;
        for (Packet packet : packetList1) {
            //System.out.println("packet" + count1 + ": " + packet);
            count1++;
        }
        //System.out.println(discoveryTestSocket1.getIncomingPackets().size());
        //System.out.println("---");


        final List<Packet> packetList2 = Stream.generate(discoveryTestSocket2::compulsoryPoll).limit(arrayBlockingQueue2.size()).collect(toList());
        int count2 = 0;
        for (Packet packet : packetList2) {
            //System.out.println("packet" + count2 + ": " + packet);
            count2++;
        }
        //System.out.println(discoveryTestSocket2.getIncomingPackets().size());


//        // Assert that all test peers received a Find Neighbors packet.
//        for (final DiscoveryTestSocket discoveryTestSocket : discoveryTestSocketList) {
//            // Five messages per peer (sent by each of the five agents).
//            final List<Packet> packets = Stream.generate(discoveryTestSocket::compulsoryPoll).limit(5).collect(toList());
//
//            // No more messages left.
//            assertThat(discoveryTestSocket.getIncomingPackets().size()).isEqualTo(0);
//
//            // Assert that the node IDs we received belong to the test agents.
//            final List<BytesValue> peerIds = packets.stream().map(Packet::getNodeId).collect(toList());
//            final List<BytesValue> nodeIds =
//                    agents
//                            .stream()
//                            .map(PeerDiscoveryAgent::getAdvertisedPeer)
//                            .map(Peer::getId)
//                            .collect(toList());
//
//            assertThat(peerIds).containsExactlyInAnyOrderElementsOf(nodeIds);
//
//            // Traverse all received packets.
//            for (final Packet packet : packets) {
//                // Assert that the packet was a Find Neighbors one.
//                assertThat(packet.getType()).isEqualTo(PacketType.PING);
//
//                // Assert on the content of the packet data.
//                final PingPacketData ping = packet.getPacketData(PingPacketData.class).get();
//                assertThat(ping.getExpiration())
//                        .isGreaterThanOrEqualTo(System.currentTimeMillis() / 1000 - 10000);
//                assertThat(ping.getTo()).isEqualTo(discoveryTestSocket.getPeer().getEndpoint());
//            }
//        }
    }

    @Test
    public void bootstrappingPeersListUpdated() {
        // Start an agent.
        final PeerDiscoveryAgent bootstrapAgent = startDiscoveryAgent(emptyList());

        // Start other five agents, pointing to the one above as a bootstrap peer.
        final List<PeerDiscoveryAgent> otherAgents =
                startDiscoveryAgents(5, singletonList(bootstrapAgent.getAdvertisedPeer()));

        final BytesValue[] otherPeersIds =
                otherAgents
                        .stream()
                        .map(PeerDiscoveryAgent::getAdvertisedPeer)
                        .map(Peer::getId)
                        .toArray(BytesValue[]::new);

        await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(
                        () ->
                                assertThat(bootstrapAgent.getPeers())
                                        .extracting(Peer::getId)
                                        .containsExactlyInAnyOrder(otherPeersIds));

        assertThat(bootstrapAgent.getPeers())
                .allMatch(p -> p.getStatus() == PeerDiscoveryStatus.BONDED);

        // This agent will bootstrap off the bootstrap peer, will add all nodes returned by the latter,
        // and will
        // bond with them, ultimately adding all 7 nodes in the network to its table.
        final PeerDiscoveryAgent newAgent =
                startDiscoveryAgent(singletonList(bootstrapAgent.getAdvertisedPeer()));
        await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(newAgent.getPeers()).hasSize(6));
    }

    @Test
    public void deconstructedIncrementalUpdateBootstrapPeersList() throws Exception {
        final int BROADCAST_TCP_PORT = 12356;
        final Vertx vertx = vertx();
        final PeerBlacklist peerBlacklist = new PeerBlacklist();

        // Start an agent.
        final DiscoveryConfiguration discoveryConfiguration_0 = new DiscoveryConfiguration();
        final DiscoveryPeer peer_0 = null;
        final List<DiscoveryPeer> peerList_0 = emptyList();
        discoveryConfiguration_0.setBootstrapPeers(peerList_0);
        discoveryConfiguration_0.setBindPort(0);

        final PeerDiscoveryAgent peerDiscoveryAgent_0 =
                new PeerDiscoveryAgent(
                        vertx,
                        SECP256K1.KeyPair.generate(),
                        discoveryConfiguration_0,
                        () -> true,
                        peerBlacklist,
                        new NodeWhitelistController(PermissioningConfiguration.createDefault()));
        peerDiscoveryAgent_0.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);
        final BytesValue id_0 = peerDiscoveryAgent_0.getAdvertisedPeer().getId();

        await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(peerDiscoveryAgent_0.getPeers()).hasSize(0));

        // Start another agent, pointing to the above agent as a bootstrap peer.
        final DiscoveryConfiguration discoveryConfiguration_1 = new DiscoveryConfiguration();
        final DiscoveryPeer peer_1 = peerDiscoveryAgent_0.getAdvertisedPeer();
        final List<DiscoveryPeer> peerList_1 = singletonList(peer_1);
        discoveryConfiguration_1.setBootstrapPeers(peerList_1);
        discoveryConfiguration_1.setBindPort(0);

        final PeerDiscoveryAgent peerDiscoveryAgent_1 =
                new PeerDiscoveryAgent(
                        vertx,
                        SECP256K1.KeyPair.generate(),
                        discoveryConfiguration_1,
                        () -> true,
                        new PeerBlacklist(),
                        new NodeWhitelistController(PermissioningConfiguration.createDefault()));

        peerDiscoveryAgent_1.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);
        final BytesValue id_1 = peerDiscoveryAgent_1.getAdvertisedPeer().getId();

        await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(peerDiscoveryAgent_0.getPeers()).hasSize(1));
        await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(peerDiscoveryAgent_1.getPeers()).hasSize(1));

        // This agent will bootstrap off the bootstrap peer, will add all nodes
        // returned by the latter, and will bond with them, ultimately adding all
        // nodes in the network to its table.
        final DiscoveryConfiguration discoveryConfiguration_TEST = new DiscoveryConfiguration();
        final DiscoveryPeer peer_TEST = peerDiscoveryAgent_0.getAdvertisedPeer();
        final List<DiscoveryPeer> peerList_TEST = singletonList(peer_TEST);
        discoveryConfiguration_TEST.setBootstrapPeers(peerList_TEST);
        discoveryConfiguration_TEST.setBindPort(0);

        final PeerDiscoveryAgent peerDiscoveryAgent_TEST =
                new PeerDiscoveryAgent(
                        vertx,
                        SECP256K1.KeyPair.generate(),
                        discoveryConfiguration_TEST,
                        () -> true,
                        peerBlacklist,
                        new NodeWhitelistController(PermissioningConfiguration.createDefault()));
        peerDiscoveryAgent_TEST.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);

        await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(peerDiscoveryAgent_TEST.getPeers()).hasSize(2));

        vertx.close();
    }
}

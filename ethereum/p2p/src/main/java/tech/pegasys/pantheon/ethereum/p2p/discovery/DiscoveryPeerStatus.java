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

import java.util.Arrays;
import java.util.List;

/** The bonded of a {@link DiscoveryPeer}, in relation to the peer discovery state machine. */
public enum DiscoveryPeerStatus {

    /**
     * Represents a newly discovered {@link DiscoveryPeer}, prior to commencing the bonding exchange.
     */
    KNOWN,

    DISPATCHED_PING_TO,

    RECEIVED_PING_FROM,

    DISPATCHED_PONG_TO,

    RECEIVED_PONG_FROM,

    DISPATCHED_FIND_NEIGHBOURS_TO, // We are asking for their neighbours.

    RECEIVED_FIND_NEIGHBOURS_FROM, // They are asking for our neighbours.

    DISPATCHED_NEIGHBOURS_TO, // We are sending them our neighbours.

    RECEIVED_NEIGHBOURS_FROM; // They are sending up their neighbours.

    @Override
    public String toString() {
        return name().toLowerCase();
    }

    public static class Lifecycle {
        public static final List<DiscoveryPeerStatus> bonded =
                Arrays.asList(
                        DiscoveryPeerStatus.DISPATCHED_PING_TO,
                        DiscoveryPeerStatus.DISPATCHED_PONG_TO,
                        DiscoveryPeerStatus.RECEIVED_PONG_FROM,
                        DiscoveryPeerStatus.DISPATCHED_FIND_NEIGHBOURS_TO,
                        DiscoveryPeerStatus.RECEIVED_FIND_NEIGHBOURS_FROM,
                        DiscoveryPeerStatus.DISPATCHED_NEIGHBOURS_TO,
                        DiscoveryPeerStatus.RECEIVED_NEIGHBOURS_FROM);
    }
}
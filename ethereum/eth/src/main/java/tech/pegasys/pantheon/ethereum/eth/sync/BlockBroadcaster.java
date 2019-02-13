/*
 * Copyright 2019 ConsenSys AG.
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
package tech.pegasys.pantheon.ethereum.eth.sync;

import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.core.Block;
import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.eth.manager.EthContext;
import tech.pegasys.pantheon.ethereum.mainnet.BlockHeaderValidator;
import tech.pegasys.pantheon.ethereum.mainnet.HeaderValidationMode;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSpec;
import tech.pegasys.pantheon.util.uint.UInt256;

import java.util.Optional;

import com.google.common.annotations.VisibleForTesting;

class BlockBroadcaster<C> {

  private final ProtocolSchedule<C> protocolSchedule;
  private final ProtocolContext<C> protocolContext;
  private final EthContext ethContext;
  private final Block block;

  BlockBroadcaster(
      final ProtocolSchedule<C> protocolSchedule,
      final ProtocolContext<C> protocolContext,
      final EthContext ethContext,
      final Block block) {
    this.protocolSchedule = protocolSchedule;
    this.protocolContext = protocolContext;
    this.ethContext = ethContext;
    this.block = block;
  }

  @VisibleForTesting
  void broadcastBlock(final Block block, final UInt256 difficulty) {
    ethContext
        .getEthPeers()
        .availablePeers()
        .forEach(ethPeer -> ethPeer.propagateBlock(block, difficulty));
  }

  void effectuateBroadcast() {
    final ProtocolSpec<C> protocolSpec =
        protocolSchedule.getByBlockNumber(block.getHeader().getNumber());
    final BlockHeaderValidator<C> blockHeaderValidator = protocolSpec.getBlockHeaderValidator();
    final Optional<BlockHeader> maybeParent =
        protocolContext.getBlockchain().getBlockHeader(block.getHeader().getParentHash());
    if (maybeParent.isPresent()) {
      final BlockHeader parent = maybeParent.get();
      if (blockHeaderValidator.validateHeader(
          block.getHeader(), parent, protocolContext, HeaderValidationMode.FULL)) {
        final UInt256 totalDifficulty =
            parent.getDifficulty().plus(block.getHeader().getDifficulty());
        broadcastBlock(block, totalDifficulty);
      }
    }
  }
}

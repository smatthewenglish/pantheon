package tech.pegasys.pantheon.ethereum.jsonrpc.internal.processor;

import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.core.Wei;
import tech.pegasys.pantheon.ethereum.worldstate.WorldStateStorage;
import tech.pegasys.pantheon.util.uint.UInt256;

import java.util.Map;

public class StateDiff {

    private WorldStateStorage storage0;

    public StateDiff(final WorldStateStorage storage0) {
        this.storage0 = storage0;
    }

    enum Effect {
        SAME, BORN, DIED, CHANGED
    }
}

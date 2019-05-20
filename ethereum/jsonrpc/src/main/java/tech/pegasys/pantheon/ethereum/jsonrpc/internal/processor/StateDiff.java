package tech.pegasys.pantheon.ethereum.jsonrpc.internal.processor;

public class StateDiff {

    private static class DiffElement {
        Effect effect;
        Object from;
        Object to;

        public DiffElement(final Effect effect, final Object from, final Object to) {
            this.effect = effect;
            this.from = from;
            this.to = to;
        }
    }

    enum Effect {
        SAME, BORN, DIED, CHANGED
    }

}

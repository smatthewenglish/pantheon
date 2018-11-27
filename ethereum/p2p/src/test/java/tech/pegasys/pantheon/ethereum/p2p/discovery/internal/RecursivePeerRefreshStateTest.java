package tech.pegasys.pantheon.ethereum.p2p.discovery.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

public class RecursivePeerRefreshStateTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void yayaya() throws Exception {

        JsonNode td = MAPPER.readTree(RecursivePeerRefreshStateTest.class.getResource("/iterative.json"));

    }
}

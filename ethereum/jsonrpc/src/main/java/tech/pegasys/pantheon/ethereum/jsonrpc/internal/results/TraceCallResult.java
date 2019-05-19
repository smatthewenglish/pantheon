package tech.pegasys.pantheon.ethereum.jsonrpc.internal.results;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import tech.pegasys.pantheon.ethereum.core.SyncStatus;

import java.util.Objects;

@JsonPropertyOrder({"id", "jsonrpc", "result"})
public class TraceCallResult implements JsonRpcResult {

    private final Object id;
    private final Object jsonrpc;
    private final Object result;
    private final Object output;
    private final Object stateDiff;

    public TraceCallResult(final Object id,
                           final Object jsonrpc,
                           final Object result,
                           final Object output,
                           final Object stateDiff) {
        this.id = id;
        this.jsonrpc = jsonrpc;
        this.result = result;
        this.output = output;
        this.stateDiff = stateDiff;
    }

    @JsonGetter(value = "id")
    public Object getId() {
        return id;
    }

    @JsonGetter(value = "jsonrpc")
    public Object getJsonrpc() {
        return jsonrpc;
    }

    @JsonGetter(value = "result")
    public Object getResult() {
        return result;
    }
}
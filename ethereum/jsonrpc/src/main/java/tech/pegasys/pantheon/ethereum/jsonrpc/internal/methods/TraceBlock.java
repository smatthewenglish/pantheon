package tech.pegasys.pantheon.ethereum.jsonrpc.internal.methods;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.pegasys.pantheon.ethereum.core.Block;
import tech.pegasys.pantheon.ethereum.core.BlockHeaderFunctions;
import tech.pegasys.pantheon.ethereum.debug.TraceOptions;
import tech.pegasys.pantheon.ethereum.jsonrpc.RpcMethod;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.JsonRpcRequest;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.JsonRpcParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.TransactionTraceParams;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.processor.BlockTrace;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.processor.BlockTracer;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.queries.BlockchainQueries;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcError;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcErrorResponse;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcResponse;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcSuccessResponse;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.results.DebugTraceTransactionResult;
import tech.pegasys.pantheon.ethereum.rlp.RLP;
import tech.pegasys.pantheon.ethereum.rlp.RLPException;
import tech.pegasys.pantheon.ethereum.vm.DebugOperationTracer;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.Collection;

public class TraceBlock implements JsonRpcMethod {

    private static final Logger LOG = LogManager.getLogger();
    private final JsonRpcParameter parameters;
    private final BlockTracer blockTracer;
    private final BlockHeaderFunctions blockHeaderFunctions;
    private final BlockchainQueries blockchain;

    public TraceBlock(
            final JsonRpcParameter parameters,
            final BlockTracer blockTracer,
            final BlockHeaderFunctions blockHeaderFunctions,
            final BlockchainQueries blockchain) {
        this.parameters = parameters;
        this.blockTracer = blockTracer;
        this.blockHeaderFunctions = blockHeaderFunctions;
        this.blockchain = blockchain;
    }

    @Override
    public String getName() {
        return RpcMethod.TRACE_BLOCK.getMethodName();
    }

    @Override
    public JsonRpcResponse response(final JsonRpcRequest request) {
        final String input = parameters.required(request.getParams(), 0, String.class);
        final Block block;
        try {
            block = Block.readFrom(RLP.input(BytesValue.fromHexString(input)), this.blockHeaderFunctions);
        } catch (final RLPException e) {
            LOG.debug("Failed to parse block RLP", e);
            return new JsonRpcErrorResponse(request.getId(), JsonRpcError.INVALID_PARAMS);
        }
        final TraceOptions traceOptions =
                parameters
                        .optional(request.getParams(), 1, TransactionTraceParams.class)
                        .map(TransactionTraceParams::traceOptions)
                        .orElse(TraceOptions.DEFAULT);

        if (this.blockchain.blockByHash(block.getHeader().getParentHash()).isPresent()) {
            final Collection<DebugTraceTransactionResult> results =
                    blockTracer
                            .trace(block, new DebugOperationTracer(traceOptions))
                            .map(BlockTrace::getTransactionTraces)
                            .map(DebugTraceTransactionResult::of)
                            .orElse(null);
            return new JsonRpcSuccessResponse(request.getId(), results);
        } else {
            return new JsonRpcErrorResponse(request.getId(), JsonRpcError.PARENT_BLOCK_NOT_FOUND);
        }
    }
}

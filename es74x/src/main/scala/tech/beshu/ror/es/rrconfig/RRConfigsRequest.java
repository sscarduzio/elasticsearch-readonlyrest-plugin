package tech.beshu.ror.es.rrconfig;

import org.elasticsearch.action.support.nodes.BaseNodesRequest;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.Arrays;

public class RRConfigsRequest extends BaseNodesRequest<RRConfigsRequest> {

    private final NodeConfigRequest nodeConfigRequest;

    @Inject
    public RRConfigsRequest(StreamInput in) throws IOException {
        super(in);
        this.nodeConfigRequest = NodeConfigRequestSerializer.parse(in.readString());
    }

    public RRConfigsRequest(NodeConfigRequest nodeConfigRequest, DiscoveryNode... concreteNodes) {
        super(concreteNodes);
        this.nodeConfigRequest = nodeConfigRequest;
    }

    public NodeConfigRequest getNodeConfigRequest() {
        return nodeConfigRequest;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(NodeConfigRequestSerializer.show(this.nodeConfigRequest));
    }

    @Override
    public String toString() {
        return "RRConfigsRequest{" +
                "concreteNodes=" + Arrays.asList(concreteNodes()) +
                '}';
    }
}

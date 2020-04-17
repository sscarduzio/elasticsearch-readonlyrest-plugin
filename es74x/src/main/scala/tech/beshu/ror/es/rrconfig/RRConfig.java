package tech.beshu.ror.es.rrconfig;

import org.elasticsearch.action.support.nodes.BaseNodeResponse;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;

public class RRConfig extends BaseNodeResponse {
    private final NodeConfig nodeConfig;

    @Inject
    public RRConfig(StreamInput in) throws IOException {
        super(in);
        this.nodeConfig = NodeConfigSerializer.parse(in.readString());
    }

    public RRConfig(DiscoveryNode discoveryNode, NodeConfig nodeConfig) {
        super(discoveryNode);
        this.nodeConfig = nodeConfig;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(NodeConfigSerializer.show(nodeConfig));
    }

    public NodeConfig getNodeConfig() {
        return nodeConfig;
    }

    @Override
    public String toString() {
        return "RRConfig{" +
                "nodeConfig=" + nodeConfig + ", " +
                "discoveryNode=" + getNode() +
                '}';
    }
}

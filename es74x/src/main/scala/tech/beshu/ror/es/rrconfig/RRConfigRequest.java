package tech.beshu.ror.es.rrconfig;

import org.elasticsearch.action.support.nodes.BaseNodeRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;

public class RRConfigRequest extends BaseNodeRequest {
    private final NodeConfigRequest nodeConfigRequest;

    public RRConfigRequest(NodeConfigRequest nodeConfigRequest) {
        super();
        this.nodeConfigRequest = nodeConfigRequest;
    }

    public RRConfigRequest(StreamInput in) throws IOException {
        super(in);
        this.nodeConfigRequest = NodeConfigRequestSerializer.parse(in.readString());
    }

    public NodeConfigRequest getNodeConfigRequest() {
        return nodeConfigRequest;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(NodeConfigRequestSerializer.show(this.nodeConfigRequest));
    }
}

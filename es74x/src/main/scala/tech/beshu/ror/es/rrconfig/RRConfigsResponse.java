package tech.beshu.ror.es.rrconfig;

import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.support.nodes.BaseNodesResponse;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RRConfigsResponse
        extends BaseNodesResponse<RRConfig>
        implements ToXContentObject {
    protected RRConfigsResponse(StreamInput in) throws IOException {
        super(in);
    }

    public RRConfigsResponse(ClusterName clusterName, List<RRConfig> nodes, List<FailedNodeException> failures) {
        super(clusterName, nodes, failures);
    }

    @Override
    protected List<RRConfig> readNodesFrom(StreamInput in) throws IOException {
        return in.readList(RRConfig::new);

    }

    @Override
    protected void writeNodesTo(StreamOutput out, List<RRConfig> nodes) throws IOException {
        out.writeList(nodes);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        Map<String, String> m = new HashMap<>();
        m.put("ko", getNodes().toString());
        builder.value(m);
        return builder;
    }

}

/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es.actions.rrconfig;

import org.elasticsearch.action.support.nodes.BaseNodesRequest;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import tech.beshu.ror.configuration.loader.distributed.NodeConfigRequest;
import tech.beshu.ror.configuration.loader.distributed.internode.NodeConfigRequestSerializer;

import java.io.IOException;
import java.util.Arrays;

public class RRConfigsRequest extends BaseNodesRequest<RRConfigsRequest> {

    private NodeConfigRequest nodeConfigRequest;

    public RRConfigsRequest() {
        super();
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
        out.writeString(NodeConfigRequestSerializer.serialize(this.nodeConfigRequest));
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        this.nodeConfigRequest = NodeConfigRequestSerializer.parse(in.readString());
    }

    @Override
    public String toString() {
        return "RRConfigsRequest{" +
                "concreteNodes=" + Arrays.asList(concreteNodes()) +
                '}';
    }
}

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
package tech.beshu.ror.es.rrconfig;

import org.elasticsearch.action.support.nodes.BaseNodeResponse;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import tech.beshu.ror.configuration.loader.distributed.NodeConfig;
import tech.beshu.ror.configuration.loader.distributed.internode.NodeConfigSerializer;

import java.io.IOException;

public class RRConfig extends BaseNodeResponse implements Writeable {
    private NodeConfig nodeConfig;

    public RRConfig() {
        super();
    }

    public RRConfig(DiscoveryNode discoveryNode, NodeConfig nodeConfig) {
        super(discoveryNode);
        this.nodeConfig = nodeConfig;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(NodeConfigSerializer.serialize(nodeConfig));
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        this.nodeConfig = NodeConfigSerializer.parse(in.readString());
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

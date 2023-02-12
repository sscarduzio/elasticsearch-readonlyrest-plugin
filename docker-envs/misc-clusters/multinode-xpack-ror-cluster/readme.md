# Purpose

It can be used to bootstrap cluster with one ROR node (built from sources) and two x-pack nodes (all masters).
Moreover, there will be two Kibanas - one connected with ROR node (installed Kibana plugin taken from ROR API) and 
the second one connected to one of X-Pack nodes. Between ES and Kibana
nodes there is a logging proxy that can be used for sniffing the communication.
ES nodes also expose ports for remote debugging.

The solution uses docker-compose. So, docker-compose and docker are required 
dependencies that have to be installed on host.

# Cluster

* **es01** - xpack ES master node
* **es02** - xpack ES master node
* **es03** - ROR ES master node
* **kbn-ror** - ROR Kibana node
* **kbn-xpack** - xpack Kibana node
* **es-ror-proxy** - logging proxy between es03 and kbn-xpack nodes 
* **es-xpack-proxy** - logging proxy between es02 and kbn-xpack nodes

There is configured SSL for ES transport and HTTP. 

# Usage

1. Configure proper versions in `.env` file
2. Change ROR configurations:
   1. `ror/es/readonlyrest.yml` 
   2. `ror/kbn/kibana.yml`
3. Run cluster:`./run.sh`
4. Play with the cluster:
   1. using Kibana: `http://localhost:15601/`, `http://localhost:25601/`
   2. calling ES nodes directly using HTTP API (9201, 9202, 9203)
   3. checking docker logs
5. Clean after you finish: `./clean.sh`

# Remote debugging

You can connect to each ES node using Remote Debugger:
* **es01** - port 5001
* **es02** - port 5002
* **es03** - port 5003


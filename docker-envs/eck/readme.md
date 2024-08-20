# README

## Requirements:
* docker installed
* kind tool installed (https://github.com/kubernetes-sigs/kind)

## RUNNING
1. Running the ECK+ROR PoC: `$ ./eck-ror-bootstrap.sh --es <ES_VESION> --kbn <KBN_VERSION>` 
   (you can pick ECK version by adding optional --eck <ECK_VERSION> param)

2. Log into Kibana `https://localhost:15601` using given credentials:
    * `admin:admin` (admin user)

3. Clean after playing with the PoC: `$ ./eck-ror-cleanup.sh`

## CUSTOMIZING
* if you have a PRO or ENTERPRISE ROR license (you can obtain one in [Customer Portal](https://readonlyrest.com/customer)) you
   can set it in `kind-cluster/ror/kbn.yml`
* initial ROR settings (when you have a PRO or ENTERPRISE ROR license you can change the ROR settings in the Admin UI) 
   can be changed in `kind-cluster/ror/ror-initial-config.yml`
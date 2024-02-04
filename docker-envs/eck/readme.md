# README

0. Requirements:
    * docker installed
    * kind tool installed (https://github.com/kubernetes-sigs/kind)
   
1. Running the ECK+ROR PoC: `$ ./eck-ror-bootstrap.sh`

2. Log into Kibana `https://localhost:15601` using given credentials:
    * `admin:admin` (admin user)
    * `user1:test` (RO user)

3. Clean after playing with the PoC: `$ ./eck-ror-cleanup.sh`

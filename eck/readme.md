# README

O. Requirements:
* docker installed
* kind tool installed (https://github.com/kubernetes-sigs/kind)

1. Running the ECK+ROR PoC:

`./eck-ror-bootstrap.sh`

Then you log to Kibana `https://localhost:15601` using given credentials:
* `admin:admin` (admin user)
* `user1:test` (RO user)

2. Cleaning after playing with the PoC:

`./eck-ror-cleanup.sh`

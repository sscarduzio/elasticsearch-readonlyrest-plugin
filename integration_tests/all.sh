read -p "Going to run ES with test-GENERIC conf. [RETURN WHEN READY] " 
bin/test.sh integration_tests/test-generic/elasticsearch.yml

read -p "Going to run ES with test-REVERSE-WILDCARD conf. [RETURN WHEN READY] " 
bin/test.sh integration_tests/test-reverse-wildcard/elasticsearch.yml

read -p "Going to run ES with test-ELK conf. [RETURN WHEN READY] " 
bin/test.sh integration_tests/test-elk/elasticsearch.yml

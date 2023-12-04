import elasticsearch_dsl, elasticsearch
from ssl import create_default_context, CERT_NONE
from elasticsearch_service import ElasticsearchService
context = create_default_context()
context.check_hostname = False
context.verify_mode = CERT_NONE
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

extra = {'scheme':'https','http_auth_username':'user2','http_auth_password':'test', 'ssl_context': context}

es=ElasticsearchService('es-ror', '9200', **extra)

q = elasticsearch_dsl.Q("range", **{"df_date": {"gte": "2023-12-01", "lte": "2024-01-01"}})

returned_data = es.get_documents_with_q('example', query=q)

print(returned_data)
print(returned_data['price'])
# PKCS#8 EC key (certbot style) + cert
openssl genpkey -algorithm EC \
-pkeyopt ec_paramgen_curve:P-384 \
-out pkcs8-ec-key.pem
openssl req -new -x509 \
-key pkcs8-ec-key.pem \
-out pkcs8-ec-cert.pem \
-days 3650 \
-subj "/CN=test"

# Traditional EC key (dehydrated style) + cert
openssl ecparam -name prime256v1 -genkey -noout \
-out traditional-ec-key.pem
openssl req -new -x509 \
-key traditional-ec-key.pem \
-out traditional-ec-cert.pem \
-days 3650 \
-subj "/CN=test"

# Traditional RSA key + cert
openssl genrsa -traditional -out traditional-rsa-key.pem 2048
openssl req -new -x509 \
-key traditional-rsa-key.pem \
-out traditional-rsa-cert.pem \
-days 3650 \
-subj "/CN=test"
# ecommerce-order-service
I will develop an e-commerce website backend (project is suggested by ChatGPT) to develop my azure, microservices and kubernetes experience
docker build -t order-service:0.0.1-SNAPSHOT -f docker/Dockerfile .
docker tag order-service:0.0.1-SNAPSHOT bmcnpnr/ecommerce/services/order-service:latest
docker push bmcnpnr/ecommerce/services/order-service:latest

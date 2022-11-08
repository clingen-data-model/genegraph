

# Server
## Add -d to detach
docker run --name myredis --network redis-network redis

# Client
docker run --network redis-network -it --rm redis redis-cli -h myredis

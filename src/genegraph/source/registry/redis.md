

# Server
## Add -d to detach
docker run --name myredis --network redis-network redis

# Client
docker run --network redis-network -it --rm redis redis-cli -h myredis



# Monitor entry counts
while true; do docker run --network redis-network -it --rm redis redis-cli -h myredis DBSIZE; sleep 60; done



# Running with a local volume and host network with a custom port
## Server
Using ./redis-data/ and a custom host port 6378 (instead of 6379)

docker run --name redis-with-vol -p 6378:6379  --rm -v $(pwd)/redis-data:/data redis

## Client
Using the custom port bound to the host localhost. Use the host network in the client container and use the custom port.

docker run --rm --net host -it redis redis-cli -p 6378


# Get db size increases over 100 seconds at 10 second intervals

for i in $(seq 1 10); do nc -w 1 vrs-cache-redis-svc 6379 <<< DBSIZE; sleep 10; done

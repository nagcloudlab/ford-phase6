


create docker bridge network
```bash
docker network create --driver bridge my-bridge-network
```

create bar-service container
```bash
docker run -d --name bar-service --network my-bridge-network nagabhushanamn/bar-service:v1  
``

inspect network
```bash
docker network inspect my-bridge-network
```

create foo-service container
```bash
docker run -p 8080:8080 -d --name foo-service --network my-bridge-network nagabhushanamn/foo-service:v1
```

remove foo-service container
```bash
docker rm -f foo-service
```

remove bar-service container
```bash
docker rm -f bar-service
```
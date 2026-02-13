# ChatFlow - WebSocket Load Testing (CS 6650 Assignment 1)


## Quick Start

### Start Server (Local)

cd server
mvn spring-boot:run    # Server runs on http://localhost:8080


### Run Client (Local)


cd client-part1
mvn exec:java -Dexec.mainClass="com.chatflow.client.ChatLoadTestClient"



## EC2 Deployment

### Build server
cd server && mvn clean package -DskipTests -q

### Build client
cd ../client-part1 && mvn clean package -DskipTests -q

### Upload to EC2
scp -i /path/to/key.pem server/target/server-1.0-SNAPSHOT.jar ec2-user@<IP>:/home/ec2-user/
rsync -az -e "ssh -i /path/to/key.pem" --exclude='target' client-part1/ ec2-user@<IP>:/home/ec2-user/


### Run on EC2

#### SSH
ssh -i /path/to/key.pem ec2-user@<IP>

#### Terminal 1: Start server
java -jar server-1.0-SNAPSHOT.jar

#### Terminal 2: Run client
cd client-part1
mvn exec:java -Dexec.mainClass="com.chatflow.client.ChatLoadTestClient"




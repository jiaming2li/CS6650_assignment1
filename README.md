# ChatFlow - WebSocket Load Testing (CS 6650 Assignment 1)

## Quick Start (Local)

### 1. Start Server

cd server
mvn spring-boot:run
#### Server runs on http://localhost:8080

### 2. Run Client (Local)

cd client-part1 && mvn package -DskipTests -q  

cd client-part1  

java -jar target/client-part1-1.0-SNAPSHOT-jar-with-dependencies.jar  


#### Or run client-part2 for detailed metrics:  

cd client-part2 && mvn package -DskipTests -q  

cd client-part2  

java -jar target/client-part2-1.0-SNAPSHOT-jar-with-dependencies.jar  


## EC2 Deployment

### Build All



cd server && mvn clean package -DskipTests -q

cd ../client-part1 && mvn clean package -DskipTests -q

cd ../client-part2 && mvn clean package -DskipTests -q


### Upload to EC2


#### Replace <KEY_PATH> and <EC2_IP> with your actual key and IP
KEY_PATH="/path/to/your/key.pem"
EC2_IP="your-ec2-ip"

scp -i "KEY_PATH" server/target/server-1.0-SNAPSHOT.jar ec2-user@EC2_IP:/home/ec2-user/  

scp -i "KEY_PATH" client-part1/target/client-part1-1.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@EC2_IP:/home/ec2-user/  

scp -i "KEY_PATH" client-part2/target/client-part2-1.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@EC2_IP:/home/ec2-user/


### Run on EC2


ssh -i <KEY_PATH> ec2-user@<EC2_IP>

java -jar server-1.0-SNAPSHOT.jar

java -jar client-part1-1.0-SNAPSHOT-jar-with-dependencies.jar

java -jar client-part2-1.0-SNAPSHOT-jar-with-dependencies.jar



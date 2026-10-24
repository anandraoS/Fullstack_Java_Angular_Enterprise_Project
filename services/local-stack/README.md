# local-stack

Docker-free infrastructure for running the whole OMS locally: an **embedded single-node KRaft Kafka** (spring-kafka-test
`EmbeddedKafkaKraftBroker`) listening on `localhost:19092`, with every contract topic and its `.DLT` pre-created with
3 partitions (`oms.order.events.v1`, `oms.inventory.commands.v1`, `oms.inventory.events.v1`, `oms.fulfillment.commands.v1`,
`oms.fulfillment.events.v1` and the five `.DLT` topics). Data is in a temp dir and lost on restart. Development tooling only, never deployed.

```
mvn -f services/pom.xml -pl local-stack -am package -DskipTests
java -jar services/local-stack/target/local-stack-1.0.0.jar            # prints "local-stack ready: Kafka bootstrap servers = localhost:19092"
java -jar services/local-stack/target/local-stack-1.0.0.jar --local-stack.kafka.port=29092   # other port
```
Stop with Ctrl+C (shutdown hook stops the broker). Start it BEFORE the services; services default to
`spring.kafka.bootstrap-servers=${OMS_KAFKA_BOOTSTRAP:localhost:19092}`.

Notes: spring-kafka-test ignores `kafkaPorts()` in KRaft mode, so the fixed port is enforced with the `listeners` /
`advertised.listeners` broker properties (covered by `LocalKafkaBrokerTest`). The topic list is duplicated as literals in
`TopicCatalog` (no runtime dependency on platform-common); a test asserts it equals `Topics.ALL_WITH_DLT`.

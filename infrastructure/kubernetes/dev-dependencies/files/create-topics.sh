#!/bin/sh
# Idempotent topic bootstrap (contract section 7). Runs inside the apache/kafka image.
set -eu
KT=/opt/kafka/bin/kafka-topics.sh
BOOTSTRAP="${BOOTSTRAP:-kafka:9092}"
PARTITIONS="${PARTITIONS:-3}"
REPLICATION="${REPLICATION:-1}"

TOPICS="oms.order.events.v1 oms.inventory.commands.v1 oms.inventory.events.v1 oms.fulfillment.commands.v1 oms.fulfillment.events.v1"

for t in $TOPICS; do
  "$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$t" \
        --partitions "$PARTITIONS" --replication-factor "$REPLICATION" \
        --config retention.ms=604800000 --config min.insync.replicas=1
  # Dead-letter topic: same partition count (DeadLetterPublishingRecoverer keeps the source partition), longer retention.
  "$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$t.DLT" \
        --partitions "$PARTITIONS" --replication-factor "$REPLICATION" \
        --config retention.ms=2592000000
done
echo "topics ready:"
"$KT" --bootstrap-server "$BOOTSTRAP" --list

// Copyright 2023 Tabular Technologies Inc.
package io.tabular.iceberg.connect.commit;

import static java.util.stream.Collectors.toList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j;
import org.apache.iceberg.util.PropertyUtil;
import org.apache.iceberg.util.SerializationUtil;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

@Log4j
public abstract class Channel {

  protected final Map<String, String> kafkaProps;
  private final String coordinatorTopic;
  private final String commitGroupId;
  private final KafkaProducer<byte[], byte[]> producer;
  private final KafkaConsumer<byte[], byte[]> consumer;
  private final Admin admin;
  private final Map<TopicPartition, OffsetAndMetadata> channelOffsets = new HashMap<>();

  private static final String COORDINATOR_TOPIC_PROP = "iceberg.coordinator.topic";
  private static final String KAFKA_PROP_PREFIX = "iceberg.kafka.";
  private static final String COMMIT_GROUP_ID_PROP = "iceberg.commit.group.id";

  public Channel(Map<String, String> props) {
    this.kafkaProps = PropertyUtil.propertiesWithPrefix(props, KAFKA_PROP_PREFIX);
    this.coordinatorTopic = props.get(COORDINATOR_TOPIC_PROP);
    this.commitGroupId = props.get(COMMIT_GROUP_ID_PROP);
    this.producer = createProducer();
    this.consumer = createConsumer();
    this.admin = createAdmin();
  }

  protected void send(Message message) {
    log.info("Sending message of type: " + message.getType().name());
    byte[] data = SerializationUtil.serializeToBytes(message);
    producer.send(new ProducerRecord<>(coordinatorTopic, data));
    producer.flush();
  }

  protected abstract void receive(Message message);

  public void process() {
    consumeAvailable(this::receive);
  }

  @SuppressWarnings("deprecation")
  protected void consumeAvailable(Consumer<Message> messageHandler) {
    // TODO: we're using the deprecated poll(long) API as it waits for metadata, better options?
    ConsumerRecords<byte[], byte[]> records = consumer.poll(0);
    while (!records.isEmpty()) {
      records.forEach(
          record -> {
            Message message = SerializationUtil.deserializeFromBytes(record.value());
            log.info("Received message of type: " + message.getType().name());
            messageHandler.accept(message);
            // the consumer stores the offsets that corresponds to the next record to consume,
            // so increment the record offset by one
            channelOffsets.put(
                new TopicPartition(record.topic(), record.partition()),
                new OffsetAndMetadata(record.offset() + 1));
          });
      records = consumer.poll(0);
    }
  }

  protected Map<TopicPartition, OffsetAndMetadata> channelOffsets() {
    return channelOffsets;
  }

  private KafkaProducer<byte[], byte[]> createProducer() {
    Map<String, Object> producerProps = new HashMap<>(kafkaProps);
    return new KafkaProducer<>(producerProps, new ByteArraySerializer(), new ByteArraySerializer());
  }

  private KafkaConsumer<byte[], byte[]> createConsumer() {
    Map<String, Object> consumerProps = new HashMap<>(kafkaProps);
    consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "cg-iceberg-" + UUID.randomUUID());
    return new KafkaConsumer<>(
        consumerProps, new ByteArrayDeserializer(), new ByteArrayDeserializer());
  }

  private Admin createAdmin() {
    Map<String, Object> adminCliProps = new HashMap<>(kafkaProps);
    return Admin.create(adminCliProps);
  }

  @SneakyThrows
  protected void seekToLastCommit() {
    ListConsumerGroupOffsetsResult response = admin.listConsumerGroupOffsets(commitGroupId);
    response.partitionsToOffsetAndMetadata().get().entrySet().stream()
        .filter(entry -> entry.getKey().topic().equals(coordinatorTopic))
        .forEach(entry -> consumer.seek(entry.getKey(), entry.getValue()));
  }

  protected Admin admin() {
    return admin;
  }

  protected String commitGroupId() {
    return commitGroupId;
  }

  @SneakyThrows
  public void start() {
    Map<String, Object> adminCliProps = new HashMap<>(kafkaProps);
    try (Admin admin = Admin.create(adminCliProps)) {
      List<TopicPartition> partitions =
          admin
              .describeTopics(List.of(coordinatorTopic))
              .topicNameValues()
              .get(coordinatorTopic)
              .get()
              .partitions()
              .stream()
              .map(info -> new TopicPartition(coordinatorTopic, info.partition()))
              .collect(toList());
      consumer.assign(partitions);
    }
  }

  @SneakyThrows
  public void stop() {
    log.info("Channel stopping");
    producer.close();
    consumer.close();
    admin.close();
  }
}
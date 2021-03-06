/*
 * Copyright 2016 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.connect.kafka;


import com.couchbase.client.core.Core;
import com.couchbase.client.core.config.BucketConfig;
import com.couchbase.client.core.config.CouchbaseBucketConfig;
import com.couchbase.client.core.env.SeedNode;
import com.couchbase.client.dcp.config.HostAndPort;
import com.couchbase.client.java.Bucket;
import com.couchbase.connect.kafka.config.source.CouchbaseSourceConfig;
import com.couchbase.connect.kafka.config.source.CouchbaseSourceTaskConfig;
import com.couchbase.connect.kafka.util.ListHelper;
import com.couchbase.connect.kafka.util.Version;
import com.couchbase.connect.kafka.util.config.ConfigHelper;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static com.couchbase.connect.kafka.util.config.ConfigHelper.keyName;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public class CouchbaseSourceConnector extends SourceConnector {
  private static final Logger LOGGER = LoggerFactory.getLogger(CouchbaseSourceConnector.class);

  private Map<String, String> configProperties;
  private CouchbaseBucketConfig bucketConfig;
  private Set<SeedNode> seedNodes;

  @Override
  public String version() {
    return Version.getVersion();
  }

  @Override
  public void start(Map<String, String> properties) {
    try {
      configProperties = properties;
      CouchbaseSourceConfig config = ConfigHelper.parse(CouchbaseSourceConfig.class, properties);

      try (KafkaCouchbaseClient client = new KafkaCouchbaseClient(config)) {
        Bucket bucket = client.bucket();
        bucketConfig = (CouchbaseBucketConfig) getConfig(bucket, config.bootstrapTimeout());
        seedNodes = getSeedNodes(client.cluster().core(), config.bootstrapTimeout());
      }

    } catch (ConfigException e) {
      throw new ConnectException("Cannot start CouchbaseSourceConnector due to configuration error", e);
    }
  }

  private static BucketConfig getConfig(Bucket bucket, Duration timeout) {
    return bucket.core()
        .configurationProvider()
        .configs()
        .flatMap(clusterConfig ->
            Mono.justOrEmpty(clusterConfig.bucketConfig(bucket.name())))
        .filter(CouchbaseSourceConnector::hasPartitionInfo)
        .blockFirst(timeout);
  }

  /**
   * Returns true unless the config is from a newly-created bucket
   * whose partition count is not yet available.
   */
  private static boolean hasPartitionInfo(BucketConfig config) {
    return ((CouchbaseBucketConfig) config).numberOfPartitions() > 0;
  }

  private static Set<SeedNode> getSeedNodes(Core core, Duration timeout) {
    return core
        .configurationProvider()
        .seedNodes()
        .blockFirst(timeout);
  }

  @Override
  public Class<? extends Task> taskClass() {
    return CouchbaseSourceTask.class;
  }

  private List<List<Integer>> splitPartitions(int maxTasks) {
    List<Integer> partitions = IntStream.range(0, bucketConfig.numberOfPartitions())
        .boxed()
        .collect(toList());

    return ListHelper.chunks(partitions, maxTasks).stream()
        .filter(list -> !list.isEmpty()) // remove empty chunks (no work for task to do)
        .collect(toList());
  }

  @Override
  public List<Map<String, String>> taskConfigs(int maxTasks) {
    String seedNodes = this.seedNodes.stream()
        .map(n -> {
          int port = n.kvPort().orElseThrow(() -> new AssertionError("seed node must have kv port"));
          return new HostAndPort(n.address(), port).format();
        })
        .collect(joining(","));

    List<List<Integer>> partitionsGrouped = splitPartitions(maxTasks);

    String partitionsKey = keyName(CouchbaseSourceTaskConfig.class, CouchbaseSourceTaskConfig::partitions);
    String dcpSeedNodesKey = keyName(CouchbaseSourceTaskConfig.class, CouchbaseSourceTaskConfig::dcpSeedNodes);

    List<Map<String, String>> taskConfigs = new ArrayList<>();
    for (List<Integer> taskPartitions : partitionsGrouped) {
      String commaDelimitedPartitions = taskPartitions.stream()
          .map(Object::toString)
          .collect(joining(","));

      Map<String, String> taskProps = new HashMap<>(configProperties);
      taskProps.put(partitionsKey, commaDelimitedPartitions);
      taskProps.put(dcpSeedNodesKey, seedNodes);
      taskConfigs.add(taskProps);
    }
    return taskConfigs;
  }

  @Override
  public void stop() {
  }

  @Override
  public ConfigDef config() {
    return ConfigHelper.define(CouchbaseSourceConfig.class);
  }
}

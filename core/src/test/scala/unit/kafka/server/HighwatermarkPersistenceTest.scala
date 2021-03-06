/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package kafka.server

import kafka.log._
import java.io.File
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.utils.{Utils, MockTime => JMockTime}
import org.easymock.EasyMock
import org.junit._
import org.junit.Assert._
import kafka.common._
import kafka.cluster.Replica
import kafka.utils.{KafkaScheduler, MockTime, SystemTime, TestUtils, ZkUtils}
import java.util.concurrent.atomic.AtomicBoolean

class HighwatermarkPersistenceTest {

  val configs = TestUtils.createBrokerConfigs(2, TestUtils.MockZkConnect).map(KafkaConfig.fromProps)
  val topic = "foo"
  val logManagers = configs map { config =>
    TestUtils.createLogManager(
      logDirs = config.logDirs.map(new File(_)).toArray,
      cleanerConfig = CleanerConfig())
  }
    
  @After
  def teardown() {
    for(manager <- logManagers; dir <- manager.logDirs)
      Utils.delete(dir)
  }

  @Test
  def testHighWatermarkPersistenceSinglePartition() {
    // mock zkclient
    val zkUtils = EasyMock.createMock(classOf[ZkUtils])
    EasyMock.replay(zkUtils)
    
    // create kafka scheduler
    val scheduler = new KafkaScheduler(2)
    scheduler.startup
    val metrics = new Metrics
    // create replica manager
    val replicaManager = new ReplicaManager(configs.head, metrics, new MockTime, new JMockTime, zkUtils, scheduler,
      logManagers(0), new AtomicBoolean(false))
    replicaManager.startup()
    try {
      replicaManager.checkpointHighWatermarks()
      var fooPartition0Hw = hwmFor(replicaManager, topic, 0)
      assertEquals(0L, fooPartition0Hw)
      val partition0 = replicaManager.getOrCreatePartition(topic, 0)
      // create leader and follower replicas
      val log0 = logManagers(0).createLog(TopicAndPartition(topic, 0), LogConfig())
      val leaderReplicaPartition0 = new Replica(configs.head.brokerId, partition0, SystemTime, 0, Some(log0))
      partition0.addReplicaIfNotExists(leaderReplicaPartition0)
      val followerReplicaPartition0 = new Replica(configs.last.brokerId, partition0, SystemTime)
      partition0.addReplicaIfNotExists(followerReplicaPartition0)
      replicaManager.checkpointHighWatermarks()
      fooPartition0Hw = hwmFor(replicaManager, topic, 0)
      assertEquals(leaderReplicaPartition0.highWatermark.messageOffset, fooPartition0Hw)
      // set the high watermark for local replica
      partition0.getReplica().get.highWatermark = new LogOffsetMetadata(5L)
      replicaManager.checkpointHighWatermarks()
      fooPartition0Hw = hwmFor(replicaManager, topic, 0)
      assertEquals(leaderReplicaPartition0.highWatermark.messageOffset, fooPartition0Hw)
      EasyMock.verify(zkUtils)
    } finally {
      // shutdown the replica manager upon test completion
      replicaManager.shutdown(false)
      metrics.close()
      scheduler.shutdown()
    }
  }

  @Test
  def testHighWatermarkPersistenceMultiplePartitions() {
    val topic1 = "foo1"
    val topic2 = "foo2"
    // mock zkclient
    val zkUtils = EasyMock.createMock(classOf[ZkUtils])
    EasyMock.replay(zkUtils)
    // create kafka scheduler
    val scheduler = new KafkaScheduler(2)
    scheduler.startup
    val metrics = new Metrics
    // create replica manager
    val replicaManager = new ReplicaManager(configs.head, metrics, new MockTime(), new JMockTime, zkUtils,
      scheduler, logManagers(0), new AtomicBoolean(false))
    replicaManager.startup()
    try {
      replicaManager.checkpointHighWatermarks()
      var topic1Partition0Hw = hwmFor(replicaManager, topic1, 0)
      assertEquals(0L, topic1Partition0Hw)
      val topic1Partition0 = replicaManager.getOrCreatePartition(topic1, 0)
      // create leader log
      val topic1Log0 = logManagers(0).createLog(TopicAndPartition(topic1, 0), LogConfig())
      // create a local replica for topic1
      val leaderReplicaTopic1Partition0 = new Replica(configs.head.brokerId, topic1Partition0, SystemTime, 0, Some(topic1Log0))
      topic1Partition0.addReplicaIfNotExists(leaderReplicaTopic1Partition0)
      replicaManager.checkpointHighWatermarks()
      topic1Partition0Hw = hwmFor(replicaManager, topic1, 0)
      assertEquals(leaderReplicaTopic1Partition0.highWatermark.messageOffset, topic1Partition0Hw)
      // set the high watermark for local replica
      topic1Partition0.getReplica().get.highWatermark = new LogOffsetMetadata(5L)
      replicaManager.checkpointHighWatermarks()
      topic1Partition0Hw = hwmFor(replicaManager, topic1, 0)
      assertEquals(5L, leaderReplicaTopic1Partition0.highWatermark.messageOffset)
      assertEquals(5L, topic1Partition0Hw)
      // add another partition and set highwatermark
      val topic2Partition0 = replicaManager.getOrCreatePartition(topic2, 0)
      // create leader log
      val topic2Log0 = logManagers(0).createLog(TopicAndPartition(topic2, 0), LogConfig())
      // create a local replica for topic2
      val leaderReplicaTopic2Partition0 =  new Replica(configs.head.brokerId, topic2Partition0, SystemTime, 0, Some(topic2Log0))
      topic2Partition0.addReplicaIfNotExists(leaderReplicaTopic2Partition0)
      replicaManager.checkpointHighWatermarks()
      var topic2Partition0Hw = hwmFor(replicaManager, topic2, 0)
      assertEquals(leaderReplicaTopic2Partition0.highWatermark.messageOffset, topic2Partition0Hw)
      // set the highwatermark for local replica
      topic2Partition0.getReplica().get.highWatermark = new LogOffsetMetadata(15L)
      assertEquals(15L, leaderReplicaTopic2Partition0.highWatermark.messageOffset)
      // change the highwatermark for topic1
      topic1Partition0.getReplica().get.highWatermark = new LogOffsetMetadata(10L)
      assertEquals(10L, leaderReplicaTopic1Partition0.highWatermark.messageOffset)
      replicaManager.checkpointHighWatermarks()
      // verify checkpointed hw for topic 2
      topic2Partition0Hw = hwmFor(replicaManager, topic2, 0)
      assertEquals(15L, topic2Partition0Hw)
      // verify checkpointed hw for topic 1
      topic1Partition0Hw = hwmFor(replicaManager, topic1, 0)
      assertEquals(10L, topic1Partition0Hw)
      EasyMock.verify(zkUtils)
    } finally {
      // shutdown the replica manager upon test completion
      replicaManager.shutdown(false)
      metrics.close()
      scheduler.shutdown()
    }
  }

  def hwmFor(replicaManager: ReplicaManager, topic: String, partition: Int): Long = {
    replicaManager.highWatermarkCheckpoints(new File(replicaManager.config.logDirs(0)).getAbsolutePath).read.getOrElse(TopicAndPartition(topic, partition), 0L)
  }
  
}

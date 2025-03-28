/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor.Assignment;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor.GroupSubscription;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor.Subscription;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.StreamsConfig.InternalConfig;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.assignment.ProcessId;
import org.apache.kafka.streams.processor.internals.assignment.AssignmentInfo;
import org.apache.kafka.streams.processor.internals.assignment.AssignorError;
import org.apache.kafka.streams.processor.internals.assignment.ReferenceContainer;
import org.apache.kafka.streams.processor.internals.assignment.SubscriptionInfo;
import org.apache.kafka.test.MockApiProcessorSupplier;
import org.apache.kafka.test.MockClientSupplier;
import org.apache.kafka.test.MockInternalTopicManager;
import org.apache.kafka.test.MockKeyValueStoreBuilder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.apache.kafka.streams.processor.internals.assignment.AssignmentTestUtils.EMPTY_CHANGELOG_END_OFFSETS;
import static org.apache.kafka.streams.processor.internals.assignment.AssignmentTestUtils.EMPTY_TASKS;
import static org.apache.kafka.streams.processor.internals.assignment.AssignmentTestUtils.PID_1;
import static org.apache.kafka.streams.processor.internals.assignment.AssignmentTestUtils.PID_2;
import static org.apache.kafka.streams.processor.internals.assignment.AssignmentTestUtils.TASK_0_0;
import static org.apache.kafka.streams.processor.internals.assignment.AssignmentTestUtils.TASK_0_1;
import static org.apache.kafka.streams.processor.internals.assignment.AssignmentTestUtils.TASK_0_2;
import static org.apache.kafka.streams.processor.internals.assignment.AssignmentTestUtils.createMockAdminClientForAssignor;
import static org.apache.kafka.streams.processor.internals.assignment.StreamsAssignmentProtocolVersions.LATEST_SUPPORTED_VERSION;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
public class HighAvailabilityStreamsPartitionAssignorTest {

    private final List<PartitionInfo> infos = asList(
        new PartitionInfo("topic1", 0, Node.noNode(), new Node[0], new Node[0]),
        new PartitionInfo("topic1", 1, Node.noNode(), new Node[0], new Node[0]),
        new PartitionInfo("topic1", 2, Node.noNode(), new Node[0], new Node[0]),
        new PartitionInfo("topic2", 0, Node.noNode(), new Node[0], new Node[0]),
        new PartitionInfo("topic2", 1, Node.noNode(), new Node[0], new Node[0]),
        new PartitionInfo("topic2", 2, Node.noNode(), new Node[0], new Node[0]),
        new PartitionInfo("topic3", 0, Node.noNode(), new Node[0], new Node[0]),
        new PartitionInfo("topic3", 1, Node.noNode(), new Node[0], new Node[0]),
        new PartitionInfo("topic3", 2, Node.noNode(), new Node[0], new Node[0]),
        new PartitionInfo("topic3", 3, Node.noNode(), new Node[0], new Node[0])
    );

    private final Cluster metadata = new Cluster(
        "cluster",
        singletonList(Node.noNode()),
        infos,
        emptySet(),
        emptySet());

    private final StreamsPartitionAssignor partitionAssignor = new StreamsPartitionAssignor();
    private final MockClientSupplier mockClientSupplier = new MockClientSupplier();
    private static final String USER_END_POINT = "localhost:8080";
    private static final String APPLICATION_ID = "stream-partition-assignor-test";

    @Mock
    private TaskManager taskManager;
    @Mock
    private Admin adminClient;
    private StreamsConfig streamsConfig = new StreamsConfig(configProps());
    private final InternalTopologyBuilder builder = new InternalTopologyBuilder();
    private TopologyMetadata topologyMetadata = new TopologyMetadata(builder, streamsConfig);
    @Mock
    private StreamsMetadataState streamsMetadataState;
    @Mock
    private Consumer<byte[], byte[]> consumer;
    private final Map<String, Subscription> subscriptions = new HashMap<>();

    private ReferenceContainer referenceContainer;
    private final MockTime time = new MockTime();

    private Map<String, Object> configProps() {
        final Map<String, Object> configurationMap = new HashMap<>();
        configurationMap.put(StreamsConfig.APPLICATION_ID_CONFIG, APPLICATION_ID);
        configurationMap.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, USER_END_POINT);
        referenceContainer = new ReferenceContainer();
        referenceContainer.mainConsumer = consumer;
        referenceContainer.adminClient = adminClient;
        referenceContainer.taskManager = taskManager;
        referenceContainer.streamsMetadataState = streamsMetadataState;
        referenceContainer.time = time;
        configurationMap.put(InternalConfig.REFERENCE_CONTAINER_PARTITION_ASSIGNOR, referenceContainer);
        return configurationMap;
    }

    // Make sure to complete setting up any mocks (such as TaskManager or AdminClient) before configuring the assignor
    private void configurePartitionAssignorWith(final Map<String, Object> props) {
        final Map<String, Object> configMap = configProps();
        configMap.putAll(props);

        streamsConfig = new StreamsConfig(configMap);
        topologyMetadata = new TopologyMetadata(builder, streamsConfig);
        partitionAssignor.configure(configMap);

        overwriteInternalTopicManagerWithMock();
    }

    private void createMockTaskManager() {
        when(taskManager.topologyMetadata()).thenReturn(topologyMetadata);
        when(taskManager.processId()).thenReturn(PID_1);
        topologyMetadata.buildAndRewriteTopology();
    }

    private void overwriteInternalTopicManagerWithMock() {
        final MockInternalTopicManager mockInternalTopicManager = new MockInternalTopicManager(
            time,
            streamsConfig,
            mockClientSupplier.restoreConsumer,
            false
        );
        partitionAssignor.setInternalTopicManager(mockInternalTopicManager);
    }

    @Test
    public void shouldReturnAllActiveTasksToPreviousOwnerRegardlessOfBalanceAndTriggerRebalanceIfEndOffsetFetchFailsAndHighAvailabilityEnabled() {
        adminClient = createMockAdminClientForAssignor(EMPTY_CHANGELOG_END_OFFSETS, true);
        final long rebalanceInterval = 5 * 60 * 1000L;

        builder.addSource(null, "source1", null, null, null, "topic1");
        builder.addProcessor("processor1", new MockApiProcessorSupplier<>(), "source1");
        builder.addStateStore(new MockKeyValueStoreBuilder("store1", false), "processor1");
        final Set<TaskId> allTasks = Set.of(TASK_0_0, TASK_0_1, TASK_0_2);

        createMockTaskManager();
        when(adminClient.listOffsets(any())).thenThrow(new StreamsException("Should be handled"));
        configurePartitionAssignorWith(singletonMap(StreamsConfig.PROBING_REBALANCE_INTERVAL_MS_CONFIG, rebalanceInterval));

        final String firstConsumer = "consumer1";
        final String newConsumer = "consumer2";

        subscriptions.put(firstConsumer,
                          new Subscription(
                              singletonList("source1"),
                              getInfo(PID_1, allTasks).encode()
                          ));
        subscriptions.put(newConsumer,
                          new Subscription(
                              singletonList("source1"),
                              getInfo(PID_2, EMPTY_TASKS).encode()
                          ));

        final Map<String, Assignment> assignments = partitionAssignor
            .assign(metadata, new GroupSubscription(subscriptions))
            .groupAssignment();

        final AssignmentInfo firstConsumerUserData = AssignmentInfo.decode(assignments.get(firstConsumer).userData());
        final List<TaskId> firstConsumerActiveTasks = firstConsumerUserData.activeTasks();
        final AssignmentInfo newConsumerUserData = AssignmentInfo.decode(assignments.get(newConsumer).userData());
        final List<TaskId> newConsumerActiveTasks = newConsumerUserData.activeTasks();

        // The tasks were returned to their prior owner
        final ArrayList<TaskId> sortedExpectedTasks = new ArrayList<>(allTasks);
        Collections.sort(sortedExpectedTasks);
        assertThat(firstConsumerActiveTasks, equalTo(sortedExpectedTasks));
        assertThat(newConsumerActiveTasks, empty());

        // There is a rebalance scheduled
        assertThat(
            time.milliseconds() + rebalanceInterval,
            anyOf(
                is(firstConsumerUserData.nextRebalanceMs()),
                is(newConsumerUserData.nextRebalanceMs())
            )
        );
    }

    @Test
    public void shouldScheduleProbingRebalanceOnThisClientIfWarmupTasksRequired() {
        final long rebalanceInterval = 5 * 60 * 1000L;

        builder.addSource(null, "source1", null, null, null, "topic1");
        builder.addProcessor("processor1", new MockApiProcessorSupplier<>(), "source1");
        builder.addStateStore(new MockKeyValueStoreBuilder("store1", false), "processor1");
        final Set<TaskId> allTasks = Set.of(TASK_0_0, TASK_0_1, TASK_0_2);

        createMockTaskManager();
        adminClient = createMockAdminClientForAssignor(getTopicPartitionOffsetsMap(
                singletonList(APPLICATION_ID + "-store1-changelog"),
                singletonList(3)),
            true);
        configurePartitionAssignorWith(singletonMap(StreamsConfig.PROBING_REBALANCE_INTERVAL_MS_CONFIG, rebalanceInterval));

        final String firstConsumer = "consumer1";
        final String newConsumer = "consumer2";

        subscriptions.put(firstConsumer,
                          new Subscription(
                              singletonList("source1"),
                              getInfo(PID_1, allTasks).encode()
                          ));
        subscriptions.put(newConsumer,
                          new Subscription(
                              singletonList("source1"),
                              getInfo(PID_2, EMPTY_TASKS).encode()
                          ));

        final Map<String, Assignment> assignments = partitionAssignor
            .assign(metadata, new GroupSubscription(subscriptions))
            .groupAssignment();

        final List<TaskId> firstConsumerActiveTasks =
            AssignmentInfo.decode(assignments.get(firstConsumer).userData()).activeTasks();
        final List<TaskId> newConsumerActiveTasks =
            AssignmentInfo.decode(assignments.get(newConsumer).userData()).activeTasks();

        final ArrayList<TaskId> sortedExpectedTasks = new ArrayList<>(allTasks);
        Collections.sort(sortedExpectedTasks);
        assertThat(firstConsumerActiveTasks, equalTo(sortedExpectedTasks));
        assertThat(newConsumerActiveTasks, empty());

        assertThat(referenceContainer.assignmentErrorCode.get(), equalTo(AssignorError.NONE.code()));

        final long nextScheduledRebalanceOnThisClient =
            AssignmentInfo.decode(assignments.get(firstConsumer).userData()).nextRebalanceMs();
        final long nextScheduledRebalanceOnOtherClient =
            AssignmentInfo.decode(assignments.get(newConsumer).userData()).nextRebalanceMs();

        assertThat(nextScheduledRebalanceOnThisClient, equalTo(time.milliseconds() + rebalanceInterval));
        assertThat(nextScheduledRebalanceOnOtherClient, equalTo(Long.MAX_VALUE));
    }


    /**
     * Helper for building the input to createMockAdminClient in cases where we don't care about the actual offsets
     * @param changelogTopics The names of all changelog topics in the topology
     * @param topicsNumPartitions The number of partitions for the corresponding changelog topic, such that the number
     *            of partitions of the ith topic in changelogTopics is given by the ith element of topicsNumPartitions
     */
    private static Map<TopicPartition, Long> getTopicPartitionOffsetsMap(final List<String> changelogTopics,
                                                                         final List<Integer> topicsNumPartitions) {
        if (changelogTopics.size() != topicsNumPartitions.size()) {
            throw new IllegalStateException("Passed in " + changelogTopics.size() + " changelog topic names, but " +
                                                topicsNumPartitions.size() + " different numPartitions for the topics");
        }
        final Map<TopicPartition, Long> changelogEndOffsets = new HashMap<>();
        for (int i = 0; i < changelogTopics.size(); ++i) {
            final String topic = changelogTopics.get(i);
            final int numPartitions = topicsNumPartitions.get(i);
            for (int partition = 0; partition < numPartitions; ++partition) {
                changelogEndOffsets.put(new TopicPartition(topic, partition), Long.MAX_VALUE);
            }
        }
        return changelogEndOffsets;
    }

    private static SubscriptionInfo getInfo(final ProcessId processId,
                                            final Set<TaskId> prevTasks) {
        return new SubscriptionInfo(
            LATEST_SUPPORTED_VERSION, LATEST_SUPPORTED_VERSION, processId, null, getTaskOffsetSums(prevTasks), (byte) 0, 0, Collections.emptyMap());
    }

    // Stub offset sums for when we only care about the prev/standby task sets, not the actual offsets
    private static Map<TaskId, Long> getTaskOffsetSums(final Set<TaskId> activeTasks) {
        final Map<TaskId, Long> taskOffsetSums = activeTasks.stream().collect(Collectors.toMap(t -> t, t -> Task.LATEST_OFFSET));
        taskOffsetSums.putAll(EMPTY_TASKS.stream().collect(Collectors.toMap(t -> t, t -> 0L)));
        return taskOffsetSums;
    }

}

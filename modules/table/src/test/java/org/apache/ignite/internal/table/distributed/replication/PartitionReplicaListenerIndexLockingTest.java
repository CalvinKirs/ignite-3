/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.table.distributed.replication;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toList;
import static org.apache.ignite.internal.table.distributed.replicator.PartitionReplicaListener.tablePartitionId;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.apache.ignite.distributed.TestPartitionDataStorage;
import org.apache.ignite.internal.catalog.CatalogService;
import org.apache.ignite.internal.catalog.descriptors.CatalogTableDescriptor;
import org.apache.ignite.internal.configuration.testframework.ConfigurationExtension;
import org.apache.ignite.internal.configuration.testframework.InjectConfiguration;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.hlc.HybridClockImpl;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.marshaller.MarshallerException;
import org.apache.ignite.internal.placementdriver.TestPlacementDriver;
import org.apache.ignite.internal.raft.service.LeaderWithTerm;
import org.apache.ignite.internal.raft.service.RaftGroupService;
import org.apache.ignite.internal.replicator.TablePartitionId;
import org.apache.ignite.internal.replicator.message.ReplicaRequest;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.schema.BinaryRowConverter;
import org.apache.ignite.internal.schema.BinaryTupleSchema;
import org.apache.ignite.internal.schema.Column;
import org.apache.ignite.internal.schema.ColumnsExtractor;
import org.apache.ignite.internal.schema.NativeTypes;
import org.apache.ignite.internal.schema.SchemaDescriptor;
import org.apache.ignite.internal.schema.configuration.GcConfiguration;
import org.apache.ignite.internal.schema.marshaller.KvMarshaller;
import org.apache.ignite.internal.schema.marshaller.reflection.ReflectionMarshallerFactory;
import org.apache.ignite.internal.storage.RowId;
import org.apache.ignite.internal.storage.impl.TestMvPartitionStorage;
import org.apache.ignite.internal.storage.index.SortedIndexStorage;
import org.apache.ignite.internal.storage.index.StorageHashIndexDescriptor;
import org.apache.ignite.internal.storage.index.StorageHashIndexDescriptor.StorageHashIndexColumnDescriptor;
import org.apache.ignite.internal.storage.index.StorageSortedIndexDescriptor;
import org.apache.ignite.internal.storage.index.StorageSortedIndexDescriptor.StorageSortedIndexColumnDescriptor;
import org.apache.ignite.internal.storage.index.impl.TestHashIndexStorage;
import org.apache.ignite.internal.storage.index.impl.TestSortedIndexStorage;
import org.apache.ignite.internal.table.distributed.HashIndexLocker;
import org.apache.ignite.internal.table.distributed.IndexLocker;
import org.apache.ignite.internal.table.distributed.LowWatermark;
import org.apache.ignite.internal.table.distributed.SortedIndexLocker;
import org.apache.ignite.internal.table.distributed.StorageUpdateHandler;
import org.apache.ignite.internal.table.distributed.TableMessagesFactory;
import org.apache.ignite.internal.table.distributed.TableSchemaAwareIndexStorage;
import org.apache.ignite.internal.table.distributed.gc.GcUpdateHandler;
import org.apache.ignite.internal.table.distributed.index.IndexUpdateHandler;
import org.apache.ignite.internal.table.distributed.replication.request.BinaryRowMessage;
import org.apache.ignite.internal.table.distributed.replicator.PartitionReplicaListener;
import org.apache.ignite.internal.table.distributed.replicator.TransactionStateResolver;
import org.apache.ignite.internal.table.distributed.replicator.action.RequestType;
import org.apache.ignite.internal.table.distributed.schema.AlwaysSyncedSchemaSyncService;
import org.apache.ignite.internal.table.impl.DummyInternalTableImpl;
import org.apache.ignite.internal.table.impl.DummySchemaManagerImpl;
import org.apache.ignite.internal.table.impl.DummySchemas;
import org.apache.ignite.internal.testframework.IgniteAbstractTest;
import org.apache.ignite.internal.tx.Lock;
import org.apache.ignite.internal.tx.LockManager;
import org.apache.ignite.internal.tx.LockMode;
import org.apache.ignite.internal.tx.TxManager;
import org.apache.ignite.internal.tx.impl.HeapLockManager;
import org.apache.ignite.internal.tx.storage.state.test.TestTxStateStorage;
import org.apache.ignite.internal.tx.test.TestTransactionIds;
import org.apache.ignite.internal.util.Lazy;
import org.apache.ignite.internal.util.Pair;
import org.apache.ignite.internal.util.PendingComparableValuesTracker;
import org.apache.ignite.network.ClusterNode;
import org.hamcrest.CustomMatcher;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** There are tests for partition replica listener. */
@ExtendWith(ConfigurationExtension.class)
public class PartitionReplicaListenerIndexLockingTest extends IgniteAbstractTest {
    private static final int PART_ID = 0;
    private static final int TABLE_ID = 1;
    private static final int PK_INDEX_ID = 1;
    private static final int HASH_INDEX_ID = 2;
    private static final int SORTED_INDEX_ID = 3;
    private static final UUID TRANSACTION_ID = TestTransactionIds.newTransactionId();
    private static final HybridClock CLOCK = new HybridClockImpl();
    private static final LockManager LOCK_MANAGER = new HeapLockManager();
    private static final TablePartitionId PARTITION_ID = new TablePartitionId(TABLE_ID, PART_ID);
    private static final TableMessagesFactory TABLE_MESSAGES_FACTORY = new TableMessagesFactory();
    private static final TestMvPartitionStorage TEST_MV_PARTITION_STORAGE = new TestMvPartitionStorage(PART_ID);

    private static SchemaDescriptor schemaDescriptor;
    private static KvMarshaller<Integer, Integer> kvMarshaller;
    private static Lazy<TableSchemaAwareIndexStorage> pkStorage;
    private static PartitionReplicaListener partitionReplicaListener;
    private static ColumnsExtractor row2HashKeyConverter;
    private static ColumnsExtractor row2SortKeyConverter;

    @BeforeAll
    public static void beforeAll(
            @InjectConfiguration GcConfiguration gcConfig
    ) {
        RaftGroupService mockRaftClient = mock(RaftGroupService.class);

        when(mockRaftClient.refreshAndGetLeaderWithTerm())
                .thenAnswer(invocationOnMock -> completedFuture(new LeaderWithTerm(null, 1L)));
        when(mockRaftClient.run(any()))
                .thenAnswer(invocationOnMock -> completedFuture(null));

        schemaDescriptor = new SchemaDescriptor(1, new Column[]{
                new Column("id".toUpperCase(Locale.ROOT), NativeTypes.INT32, false),
        }, new Column[]{
                new Column("val".toUpperCase(Locale.ROOT), NativeTypes.INT32, false),
        });

        row2HashKeyConverter = BinaryRowConverter.keyExtractor(schemaDescriptor);

        StorageHashIndexDescriptor pkIndexDescriptor = new StorageHashIndexDescriptor(
                PK_INDEX_ID,
                List.of(new StorageHashIndexColumnDescriptor("ID", NativeTypes.INT32, false))
        );

        TableSchemaAwareIndexStorage hashIndexStorage = new TableSchemaAwareIndexStorage(
                PK_INDEX_ID,
                new TestHashIndexStorage(PART_ID, pkIndexDescriptor),
                row2HashKeyConverter
        );
        pkStorage = new Lazy<>(() -> hashIndexStorage);

        IndexLocker pkLocker = new HashIndexLocker(PK_INDEX_ID, true, LOCK_MANAGER, row2HashKeyConverter);
        IndexLocker hashIndexLocker = new HashIndexLocker(HASH_INDEX_ID, false, LOCK_MANAGER, row2HashKeyConverter);

        BinaryTupleSchema rowSchema = BinaryTupleSchema.createRowSchema(schemaDescriptor);
        BinaryTupleSchema valueSchema = BinaryTupleSchema.createValueSchema(schemaDescriptor);

        row2SortKeyConverter = new BinaryRowConverter(rowSchema, valueSchema);

        TableSchemaAwareIndexStorage sortedIndexStorage = new TableSchemaAwareIndexStorage(
                SORTED_INDEX_ID,
                new TestSortedIndexStorage(PART_ID,
                        new StorageSortedIndexDescriptor(
                                SORTED_INDEX_ID,
                                List.of(new StorageSortedIndexColumnDescriptor(
                                        "val", NativeTypes.INT32, false, true
                                ))
                        )),
                row2SortKeyConverter
        );

        IndexLocker sortedIndexLocker = new SortedIndexLocker(
                SORTED_INDEX_ID,
                PART_ID,
                LOCK_MANAGER,
                (SortedIndexStorage) sortedIndexStorage.storage(),
                row2SortKeyConverter
        );

        DummySchemaManagerImpl schemaManager = new DummySchemaManagerImpl(schemaDescriptor);
        PendingComparableValuesTracker<HybridTimestamp, Void> safeTime = new PendingComparableValuesTracker<>(CLOCK.now());

        IndexUpdateHandler indexUpdateHandler = new IndexUpdateHandler(
                DummyInternalTableImpl.createTableIndexStoragesSupplier(Map.of(pkStorage.get().id(), pkStorage.get()))
        );

        TestPartitionDataStorage partitionDataStorage = new TestPartitionDataStorage(TABLE_ID, PART_ID, TEST_MV_PARTITION_STORAGE);

        CatalogService catalogService = mock(CatalogService.class);
        when(catalogService.table(anyInt(), anyLong())).thenReturn(mock(CatalogTableDescriptor.class));

        ClusterNode localNode = mock(ClusterNode.class);

        partitionReplicaListener = new PartitionReplicaListener(
                TEST_MV_PARTITION_STORAGE,
                mockRaftClient,
                mock(TxManager.class),
                LOCK_MANAGER,
                Runnable::run,
                PART_ID,
                TABLE_ID,
                () -> Map.of(
                        pkLocker.id(), pkLocker,
                        hashIndexLocker.id(), hashIndexLocker,
                        sortedIndexLocker.id(), sortedIndexLocker
                ),
                pkStorage,
                () -> Map.of(
                        sortedIndexLocker.id(), sortedIndexStorage,
                        hashIndexLocker.id(), hashIndexStorage
                ),
                CLOCK,
                safeTime,
                new TestTxStateStorage(),
                mock(TransactionStateResolver.class),
                new StorageUpdateHandler(
                        PART_ID,
                        partitionDataStorage,
                        gcConfig,
                        mock(LowWatermark.class),
                        indexUpdateHandler,
                        new GcUpdateHandler(partitionDataStorage, safeTime, indexUpdateHandler)
                ),
                new DummySchemas(schemaManager),
                localNode,
                new AlwaysSyncedSchemaSyncService(),
                catalogService,
                new TestPlacementDriver(localNode.name())
        );

        kvMarshaller = new ReflectionMarshallerFactory().create(schemaDescriptor, Integer.class, Integer.class);
    }

    @BeforeEach
    public void beforeTest() {
        ((TestHashIndexStorage) pkStorage.get().storage()).clear();
        TEST_MV_PARTITION_STORAGE.clear();

        locks().forEach(LOCK_MANAGER::release);
    }

    /** Verifies the mode in which the lock was acquired on the index key for a particular operation. */
    @ParameterizedTest
    @MethodSource("readWriteSingleTestArguments")
    void testReadWriteSingle(ReadWriteTestArg arg) throws MarshallerException {
        BinaryRow testPk = kvMarshaller.marshal(1);
        BinaryRow testBinaryRow = kvMarshaller.marshal(1, 1);

        if (arg.type != RequestType.RW_INSERT) {
            var rowId = new RowId(PART_ID);
            insertRows(List.of(new Pair<>(testBinaryRow, rowId)), TestTransactionIds.newTransactionId());
        }

        ReplicaRequest request;

        switch (arg.type) {
            case RW_DELETE:
            case RW_GET_AND_DELETE:
                request = TABLE_MESSAGES_FACTORY.readWriteSingleRowPkReplicaRequest()
                        .groupId(PARTITION_ID)
                        .term(1L)
                        .commitPartitionId(tablePartitionId(PARTITION_ID))
                        .transactionId(TRANSACTION_ID)
                        .primaryKey(testPk.tupleSlice())
                        .requestType(arg.type)
                        .build();

                break;

            case RW_DELETE_EXACT:
            case RW_INSERT:
            case RW_UPSERT:
            case RW_REPLACE_IF_EXIST:
            case RW_GET_AND_REPLACE:
            case RW_GET_AND_UPSERT:
                request = TABLE_MESSAGES_FACTORY.readWriteSingleRowReplicaRequest()
                        .groupId(PARTITION_ID)
                        .term(1L)
                        .commitPartitionId(tablePartitionId(PARTITION_ID))
                        .transactionId(TRANSACTION_ID)
                        .binaryRowMessage(binaryRowMessage(testBinaryRow))
                        .requestType(arg.type)
                        .build();
                break;

            default:
                throw new AssertionError("Unexpected operation type: " + arg.type);
        }

        CompletableFuture<?> fut = partitionReplicaListener.invoke(request, "local");

        await(fut);

        assertThat(
                locks(),
                allOf(
                        hasItem(lockThat(
                                arg.expectedLockOnUniqueHash + " on unique hash index",
                                lock -> Objects.equals(PK_INDEX_ID, lock.lockKey().contextId())
                                        && lock.lockMode() == arg.expectedLockOnUniqueHash
                        )),
                        hasItem(lockThat(
                                arg.expectedLockOnNonUniqueHash + " on non unique hash index",
                                lock -> Objects.equals(HASH_INDEX_ID, lock.lockKey().contextId())
                                        && lock.lockMode() == arg.expectedLockOnNonUniqueHash
                        )),
                        hasItem(lockThat(
                                arg.expectedLockOnSort + " on sorted index",
                                lock -> Objects.equals(SORTED_INDEX_ID, lock.lockKey().contextId())
                                        && lock.lockMode() == arg.expectedLockOnSort
                        ))
                )
        );
    }

    /** Verifies the mode in which the lock was acquired on the index key for a particular operation. */
    @ParameterizedTest
    @MethodSource("readWriteMultiTestArguments")
    void testReadWriteMulti(ReadWriteTestArg arg) throws MarshallerException {
        var pks = new ArrayList<BinaryRow>();
        var rows = new ArrayList<BinaryRow>();

        for (int i = 1; i <= 3; i++) {
            pks.add(kvMarshaller.marshal(i));
            rows.add(kvMarshaller.marshal(i, i));
        }

        if (arg.type != RequestType.RW_INSERT_ALL) {
            for (BinaryRow row : rows) {
                var rowId = new RowId(PART_ID);
                insertRows(List.of(new Pair<>(row, rowId)), TestTransactionIds.newTransactionId());
            }
        }

        ReplicaRequest request;

        switch (arg.type) {
            case RW_DELETE_ALL:
                request = TABLE_MESSAGES_FACTORY.readWriteMultiRowPkReplicaRequest()
                        .groupId(PARTITION_ID)
                        .term(1L)
                        .commitPartitionId(tablePartitionId(PARTITION_ID))
                        .transactionId(TRANSACTION_ID)
                        .primaryKeys(pks.stream().map(BinaryRow::tupleSlice).collect(toList()))
                        .requestType(arg.type)
                        .build();

                break;

            case RW_DELETE_EXACT_ALL:
            case RW_INSERT_ALL:
            case RW_UPSERT_ALL:
                request = TABLE_MESSAGES_FACTORY.readWriteMultiRowReplicaRequest()
                        .groupId(PARTITION_ID)
                        .term(1L)
                        .commitPartitionId(tablePartitionId(PARTITION_ID))
                        .transactionId(TRANSACTION_ID)
                        .binaryRowMessages(rows.stream().map(PartitionReplicaListenerIndexLockingTest::binaryRowMessage).collect(toList()))
                        .requestType(arg.type)
                        .build();

                break;

            default:
                throw new AssertionError("Unexpected operation type: " + arg.type);
        }

        CompletableFuture<?> fut = partitionReplicaListener.invoke(request, "local");

        await(fut);

        for (BinaryRow row : rows) {
            assertThat(
                    locks(),
                    allOf(
                            hasItem(lockThat(
                                    arg.expectedLockOnUniqueHash + " on unique hash index",
                                    lock -> Objects.equals(PK_INDEX_ID, lock.lockKey().contextId())
                                            && row2HashKeyConverter.extractColumns(row).byteBuffer().equals(lock.lockKey().key())
                                            && lock.lockMode() == arg.expectedLockOnUniqueHash
                            )),
                            hasItem(lockThat(
                                    arg.expectedLockOnNonUniqueHash + " on non unique hash index",
                                    lock -> Objects.equals(HASH_INDEX_ID, lock.lockKey().contextId())
                                            && row2HashKeyConverter.extractColumns(row).byteBuffer().equals(lock.lockKey().key())
                                            && lock.lockMode() == arg.expectedLockOnNonUniqueHash
                            )),
                            hasItem(lockThat(
                                    arg.expectedLockOnSort + " on sorted index",
                                    lock -> Objects.equals(SORTED_INDEX_ID, lock.lockKey().contextId())
                                            && row2SortKeyConverter.extractColumns(row).byteBuffer().equals(lock.lockKey().key())
                                            && lock.lockMode() == arg.expectedLockOnSort
                            ))
                    )
            );
        }
    }

    private static Iterable<ReadWriteTestArg> readWriteSingleTestArguments() {
        return List.of(
                new ReadWriteTestArg(RequestType.RW_DELETE, LockMode.X, LockMode.IX, LockMode.IX),
                new ReadWriteTestArg(RequestType.RW_DELETE_EXACT, LockMode.X, LockMode.IX, LockMode.IX),
                new ReadWriteTestArg(RequestType.RW_INSERT, LockMode.X, LockMode.IX, LockMode.X),
                new ReadWriteTestArg(RequestType.RW_UPSERT, LockMode.X, LockMode.IX, LockMode.X),
                new ReadWriteTestArg(RequestType.RW_REPLACE_IF_EXIST, LockMode.X, LockMode.IX, LockMode.X),

                new ReadWriteTestArg(RequestType.RW_GET_AND_DELETE, LockMode.X, LockMode.IX, LockMode.IX),
                new ReadWriteTestArg(RequestType.RW_GET_AND_REPLACE, LockMode.X, LockMode.IX, LockMode.X),
                new ReadWriteTestArg(RequestType.RW_GET_AND_UPSERT, LockMode.X, LockMode.IX, LockMode.X)
        );
    }

    private static Iterable<ReadWriteTestArg> readWriteMultiTestArguments() {
        return List.of(
                new ReadWriteTestArg(RequestType.RW_DELETE_ALL, LockMode.X, LockMode.IX, LockMode.IX),
                new ReadWriteTestArg(RequestType.RW_DELETE_EXACT_ALL, LockMode.X, LockMode.IX, LockMode.IX),
                new ReadWriteTestArg(RequestType.RW_INSERT_ALL, LockMode.X, LockMode.IX, LockMode.X),
                new ReadWriteTestArg(RequestType.RW_UPSERT_ALL, LockMode.X, LockMode.IX, LockMode.X)
        );
    }

    private List<Lock> locks() {
        List<Lock> locks = new ArrayList<>();

        Iterator<Lock> it = LOCK_MANAGER.locks(TRANSACTION_ID);

        while (it.hasNext()) {
            locks.add(it.next());
        }

        return locks;
    }

    private void insertRows(List<Pair<BinaryRow, RowId>> rows, UUID txId) {
        HybridTimestamp commitTs = CLOCK.now();

        for (Pair<BinaryRow, RowId> row : rows) {
            BinaryRow binaryRow = row.getFirst();
            RowId rowId = row.getSecond();

            pkStorage.get().put(binaryRow, rowId);
            TEST_MV_PARTITION_STORAGE.addWrite(rowId, binaryRow, txId, TABLE_ID, PART_ID);
            TEST_MV_PARTITION_STORAGE.commitWrite(rowId, commitTs);
        }
    }

    private static Matcher<Lock> lockThat(String description, Function<Lock, Boolean> checker) {
        return new CustomMatcher<>(description) {
            @Override
            public boolean matches(Object actual) {
                return actual instanceof Lock && checker.apply((Lock) actual) == Boolean.TRUE;
            }
        };
    }

    private static BinaryRowMessage binaryRowMessage(BinaryRow binaryRow) {
        return TABLE_MESSAGES_FACTORY.binaryRowMessage()
                .binaryTuple(binaryRow.tupleSlice())
                .schemaVersion(binaryRow.schemaVersion())
                .build();
    }

    static class ReadWriteTestArg {
        private final RequestType type;
        private final LockMode expectedLockOnUniqueHash;
        private final LockMode expectedLockOnNonUniqueHash;
        private final LockMode expectedLockOnSort;

        public ReadWriteTestArg(
                RequestType type,
                LockMode expectedLockOnUniqueHash,
                LockMode expectedLockOnNonUniqueHash,
                LockMode expectedLockOnSort
        ) {
            this.type = type;
            this.expectedLockOnUniqueHash = expectedLockOnUniqueHash;
            this.expectedLockOnNonUniqueHash = expectedLockOnNonUniqueHash;
            this.expectedLockOnSort = expectedLockOnSort;
        }

        @Override
        public String toString() {
            return type.toString();
        }
    }
}

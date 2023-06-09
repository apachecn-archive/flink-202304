/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.base.source.reader.mocks;

import org.apache.flink.api.connector.source.mocks.MockSourceSplit;
import org.apache.flink.connector.base.source.reader.RecordsBySplits;
import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.splitreader.SplitReader;
import org.apache.flink.connector.base.source.reader.splitreader.SplitsAddition;
import org.apache.flink.connector.base.source.reader.splitreader.SplitsChange;
import org.apache.flink.connector.base.source.reader.splitreader.SplitsRemoval;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A mock split reader for unit tests. The mock split reader provides configurable behaviours. 1.
 * Blocking fetch or non blocking fetch. - A blocking fetch can only be waken up by an interruption.
 * - A non-blocking fetch do not expect to be interrupted. 2. handle splits changes in one
 * handleSplitsChanges call or handle one change in each call of handleSplitsChanges.
 */
public class MockSplitReader implements SplitReader<int[], MockSourceSplit> {
    // Use LinkedHashMap for determinism.
    private final Map<String, MockSourceSplit> splits = new LinkedHashMap<>();
    private final int numRecordsPerSplitPerFetch;
    private final boolean separatedFinishedRecord;
    private final boolean blockingFetch;

    private final Object wakeupLock = new Object();
    private volatile Thread threadInBlocking;
    private boolean wokenUp;
    private Set<MockSourceSplit> pausedSplits = new HashSet<>();
    private Set<String> removedSplits = new HashSet<>();

    protected MockSplitReader(
            int numRecordsPerSplitPerFetch,
            boolean separatedFinishedRecord,
            boolean blockingFetch) {
        this.numRecordsPerSplitPerFetch = numRecordsPerSplitPerFetch;
        this.separatedFinishedRecord = separatedFinishedRecord;
        this.blockingFetch = blockingFetch;
    }

    @Override
    public RecordsWithSplitIds<int[]> fetch() {
        return getRecords();
    }

    @Override
    public void handleSplitsChanges(SplitsChange<MockSourceSplit> splitsChange) {
        if (splitsChange instanceof SplitsAddition) {
            splitsChange.splits().forEach(s -> splits.put(s.splitId(), s));
        } else if (splitsChange instanceof SplitsRemoval) {
            splitsChange.splits().forEach(s -> splits.remove(s.splitId()));
            removedSplits.addAll(
                    splitsChange.splits().stream()
                            .map(MockSourceSplit::splitId)
                            .collect(Collectors.toSet()));
        } else {
            throw new IllegalArgumentException("Do not recognize split change: " + splitsChange);
        }
    }

    @Override
    public void wakeUp() {
        synchronized (wakeupLock) {
            wokenUp = true;
            if (threadInBlocking != null) {
                threadInBlocking.interrupt();
            }
        }
    }

    @Override
    public void close() throws Exception {}

    private RecordsBySplits<int[]> getRecords() {
        final RecordsBySplits.Builder<int[]> records = new RecordsBySplits.Builder<>();

        // after this locked section, the thread might be interrupted
        synchronized (wakeupLock) {
            if (wokenUp) {
                wokenUp = false;
                markFinishedSplits(records);
                return records.build();
            }
            threadInBlocking = Thread.currentThread();
        }

        try {
            Iterator<Map.Entry<String, MockSourceSplit>> iterator = splits.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, MockSourceSplit> entry = iterator.next();
                MockSourceSplit split = entry.getValue();
                if (pausedSplits.contains(split)) {
                    continue;
                }

                boolean hasRecords = false;
                for (int i = 0; i < numRecordsPerSplitPerFetch && !split.isFinished(); i++) {
                    // This call may throw InterruptedException.
                    int[] record = split.getNext(blockingFetch);
                    if (record != null) {
                        records.add(entry.getKey(), record);
                        hasRecords = true;
                    }
                }
                if (split.isFinished()) {
                    if (!separatedFinishedRecord) {
                        records.addFinishedSplit(entry.getKey());
                        iterator.remove();
                    } else if (!hasRecords) {
                        records.addFinishedSplit(entry.getKey());
                        iterator.remove();
                        break;
                    }
                }
            }
        } catch (InterruptedException ie) {
            // Catch the exception and return the records that are already read.
            if (!blockingFetch) {
                throw new RuntimeException("Caught unexpected interrupted exception.");
            }
        } finally {
            // after this locked section, the thread may not be interrupted any more
            synchronized (wakeupLock) {
                wokenUp = false;
                //noinspection ResultOfMethodCallIgnored
                Thread.interrupted();
                threadInBlocking = null;
            }
        }
        markFinishedSplits(records);
        return records.build();
    }

    @Override
    public void pauseOrResumeSplits(
            Collection<MockSourceSplit> splitsToPause, Collection<MockSourceSplit> splitsToResume) {
        if (!splitsToPause.isEmpty()) {
            assertThat(pausedSplits).doesNotContainAnyElementsOf(splitsToPause);
        }
        pausedSplits.addAll(splitsToPause);
        assertThat(pausedSplits).containsAll(splitsToResume);
        pausedSplits.removeAll(splitsToResume);
    }

    private void markFinishedSplits(RecordsBySplits.Builder recordsBuilder) {
        if (!removedSplits.isEmpty()) {
            recordsBuilder.addFinishedSplits(removedSplits);
            removedSplits.clear();
        }
    }

    /** Builder for {@link MockSplitReader}. */
    public static class Builder {
        protected int numRecordsPerSplitPerFetch = 2;
        protected boolean separatedFinishedRecord = false;
        protected boolean blockingFetch = false;

        protected Builder() {}

        protected Builder(Builder other) {
            this.numRecordsPerSplitPerFetch = other.numRecordsPerSplitPerFetch;
            this.separatedFinishedRecord = other.separatedFinishedRecord;
            this.blockingFetch = other.blockingFetch;
        }

        public Builder setNumRecordsPerSplitPerFetch(int numRecordsPerSplitPerFetch) {
            this.numRecordsPerSplitPerFetch = numRecordsPerSplitPerFetch;
            return this;
        }

        public Builder setSeparatedFinishedRecord(boolean separatedFinishedRecord) {
            this.separatedFinishedRecord = separatedFinishedRecord;
            return this;
        }

        public Builder setBlockingFetch(boolean blockingFetch) {
            this.blockingFetch = blockingFetch;
            return this;
        }

        public MockSplitReader build() {
            return new MockSplitReader(
                    numRecordsPerSplitPerFetch, separatedFinishedRecord, blockingFetch);
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }
}

/*
 *  Copyright 2016-2019 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.hollow.api.client;

import com.netflix.hollow.api.consumer.HollowConsumer;
import com.netflix.hollow.api.consumer.HollowConsumer.TransitionAwareRefreshListener;
import com.netflix.hollow.api.custom.HollowAPI;
import com.netflix.hollow.core.HollowConstants;
import com.netflix.hollow.core.memory.encoding.BlobByteBuffer;
import com.netflix.hollow.core.read.dataaccess.HollowDataAccess;
import com.netflix.hollow.core.read.dataaccess.proxy.HollowProxyDataAccess;
import com.netflix.hollow.core.read.engine.HollowBlobReader;
import com.netflix.hollow.core.read.engine.HollowReadStateEngine;
import com.netflix.hollow.core.read.filter.HollowFilterConfig;
import com.netflix.hollow.tools.history.HollowHistoricalStateCreator;
import com.netflix.hollow.tools.history.HollowHistoricalStateDataAccess;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.nio.channels.FileChannel;

/**
 * A class comprising much of the internal state of a {@link HollowConsumer}.  Not intended for external consumption.
 */
class HollowDataHolder {

    private final HollowReadStateEngine stateEngine;
    private final HollowAPIFactory apiFactory;
    private final HollowBlobReader reader;
    private final HollowConsumer.DoubleSnapshotConfig doubleSnapshotConfig;
    private final FailedTransitionTracker failedTransitionTracker;
    private final StaleHollowReferenceDetector staleReferenceDetector;
    private final HollowConsumer.ObjectLongevityConfig objLongevityConfig;

    private HollowFilterConfig filter;

    private HollowAPI currentAPI;

    private WeakReference<HollowHistoricalStateDataAccess> priorHistoricalDataAccess;

    private long currentVersion = HollowConstants.VERSION_NONE;

    HollowDataHolder(HollowReadStateEngine stateEngine,
                            HollowAPIFactory apiFactory,
                            HollowConsumer.DoubleSnapshotConfig doubleSnapshotConfig,
                            FailedTransitionTracker failedTransitionTracker, 
                            StaleHollowReferenceDetector staleReferenceDetector, 
                            HollowConsumer.ObjectLongevityConfig objLongevityConfig) {
        this.stateEngine = stateEngine;
        this.apiFactory = apiFactory;
        this.reader = new HollowBlobReader(stateEngine);
        this.doubleSnapshotConfig = doubleSnapshotConfig;
        this.failedTransitionTracker = failedTransitionTracker;
        this.staleReferenceDetector = staleReferenceDetector;
        this.objLongevityConfig = objLongevityConfig;
    }

    HollowReadStateEngine getStateEngine() {
        return stateEngine;
    }

    HollowAPI getAPI() {
        return currentAPI;
    }

    long getCurrentVersion() {
        return currentVersion;
    }

    HollowDataHolder setFilter(HollowFilterConfig filter) {
        this.filter = filter;
        return this;
    }

    void update(HollowUpdatePlan updatePlan, HollowConsumer.RefreshListener[] refreshListeners) throws Throwable {
        // Only fail if double snapshot is configured.
        // This is a short term solution until it is decided to either remove this feature
        // or refine it.
        // If the consumer is configured to only follow deltas (no double snapshot) then
        // any failure to transition will cause the consumer to become "stuck" on stale data
        // unless the failed transitions are explicitly cleared or a new consumer is created.
        // A transition failure is very broad encompassing many forms of transitory failure,
        // such as network failures when accessing a blob, where the consumer might recover,
        // such as when a new delta is published.
        // Note that a refresh listener may also induce a failed transition, likely unknowingly,
        // by throwing an exception.
        if(doubleSnapshotConfig.allowDoubleSnapshot() && failedTransitionTracker.anyTransitionWasFailed(updatePlan)) {
            throw new RuntimeException("Update plan contains known failing transition!");
        }

        if(updatePlan.isSnapshotPlan())
            applySnapshotPlan(updatePlan, refreshListeners);
        else
            applyDeltaOnlyPlan(updatePlan, refreshListeners);
    }

    private void applySnapshotPlan(HollowUpdatePlan updatePlan, HollowConsumer.RefreshListener[] refreshListeners) throws Throwable {
        applySnapshotTransition(updatePlan.getSnapshotTransition(), refreshListeners);
            
        for(HollowConsumer.Blob blob : updatePlan.getDeltaTransitions()) {
            applyDeltaTransition(blob, true, refreshListeners);
        }

        try {
            for(HollowConsumer.RefreshListener refreshListener : refreshListeners)
                refreshListener.snapshotUpdateOccurred(currentAPI, stateEngine, updatePlan.destinationVersion());
        } catch(Throwable t) {
            failedTransitionTracker.markAllTransitionsAsFailed(updatePlan);
            throw t;
        }
    }

    private void applySnapshotTransition(HollowConsumer.Blob snapshotBlob, HollowConsumer.RefreshListener[] refreshListeners) throws Throwable {


        RandomAccessFile raf = new RandomAccessFile(snapshotBlob.getFile(), "r");
        FileChannel channel = raf.getChannel();
        BlobByteBuffer buffer = BlobByteBuffer.mmapBlob(channel);
        BufferedWriter debug = new BufferedWriter(new FileWriter("/tmp/debug_new"));

        try {
            applyStateEngineTransition(raf, buffer, debug, snapshotBlob, refreshListeners);
            initializeAPI();

            for(HollowConsumer.RefreshListener refreshListener : refreshListeners) {
                if (refreshListener instanceof TransitionAwareRefreshListener)
                    ((TransitionAwareRefreshListener)refreshListener).snapshotApplied(currentAPI, stateEngine, snapshotBlob.getToVersion());
            }
        } catch(Throwable t) {
            failedTransitionTracker.markFailedTransition(snapshotBlob);
            throw t;
        }
    }


    private void applyStateEngineTransition(RandomAccessFile raf, BlobByteBuffer buffer, BufferedWriter debug, HollowConsumer.Blob transition, HollowConsumer.RefreshListener[] refreshListeners) throws IOException {
        if(transition.isSnapshot()) {
            if(filter == null)
                reader.readSnapshot(raf, buffer, debug);
            else
                reader.readSnapshot(raf, buffer, debug, filter);
        } else {
            throw new UnsupportedOperationException();
            // reader.applyDelta(is);
        }

        setVersion(transition.getToVersion());

        for(HollowConsumer.RefreshListener refreshListener : refreshListeners)
            refreshListener.blobLoaded(transition);
    }

    private void initializeAPI() {
        if(objLongevityConfig.enableLongLivedObjectSupport()) {
            HollowProxyDataAccess dataAccess = new HollowProxyDataAccess();
            dataAccess.setDataAccess(stateEngine);
            currentAPI = apiFactory.createAPI(dataAccess);
        } else {
            currentAPI = apiFactory.createAPI(stateEngine);
        }
        
        staleReferenceDetector.newAPIHandle(currentAPI);
    }

    private void applyDeltaOnlyPlan(HollowUpdatePlan updatePlan, HollowConsumer.RefreshListener[] refreshListeners) throws Throwable {
        for(HollowConsumer.Blob blob : updatePlan) {
            applyDeltaTransition(blob, false, refreshListeners);
        }
    }

    private void applyDeltaTransition(HollowConsumer.Blob blob, boolean isSnapshotPlan, HollowConsumer.RefreshListener[] refreshListeners) throws Throwable {
        throw new UnsupportedOperationException();
    }

    private void wireHistoricalStateChain(HollowHistoricalStateDataAccess nextPriorState) {
        if(priorHistoricalDataAccess != null) {
            HollowHistoricalStateDataAccess dataAccess = priorHistoricalDataAccess.get();
            if(dataAccess != null) {
                dataAccess.setNextState(nextPriorState);
            }
        }

        priorHistoricalDataAccess = new WeakReference<HollowHistoricalStateDataAccess>(nextPriorState);
    }

    private void setVersion(long version) {
        currentVersion = version;
    }

}

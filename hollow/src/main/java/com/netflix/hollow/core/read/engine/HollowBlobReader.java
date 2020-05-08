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
package com.netflix.hollow.core.read.engine;

import com.netflix.hollow.core.HollowBlobHeader;
import com.netflix.hollow.core.memory.encoding.VarInt;
import com.netflix.hollow.core.read.HollowBlobInput;
import com.netflix.hollow.core.read.engine.list.HollowListTypeReadState;
import com.netflix.hollow.core.read.engine.map.HollowMapTypeReadState;
import com.netflix.hollow.core.read.engine.object.HollowObjectTypeReadState;
import com.netflix.hollow.core.read.engine.set.HollowSetTypeReadState;
import com.netflix.hollow.core.read.filter.HollowFilterConfig;
import com.netflix.hollow.core.schema.HollowListSchema;
import com.netflix.hollow.core.schema.HollowMapSchema;
import com.netflix.hollow.core.schema.HollowObjectSchema;
import com.netflix.hollow.core.schema.HollowSchema;
import com.netflix.hollow.core.schema.HollowSetSchema;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.util.Collection;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * A HollowBlobReader is used to populate and update data in a {@link HollowReadStateEngine}, via the consumption
 * of snapshot and delta blobs.
 */
public class HollowBlobReader {

    private final Logger log = Logger.getLogger(HollowBlobReader.class.getName());
    private final HollowReadStateEngine stateEngine;
    private final HollowBlobHeaderReader headerReader;

    public HollowBlobReader(HollowReadStateEngine stateEngine) {
        this(stateEngine, new HollowBlobHeaderReader());
    }

    public HollowBlobReader(HollowReadStateEngine stateEngine, HollowBlobHeaderReader headerReader) {
        this.stateEngine = stateEngine;
        this.headerReader = headerReader;
    }

    /**
     * Initialize the state engine using a snapshot blob from the provided HollowBlobInput.
     *
     * @param in the Hollow blob input to read the snapshot from
     * @throws IOException if the snapshot could not be read
     */
    public void readSnapshot(HollowBlobInput in, BufferedWriter debug) throws IOException {
        readSnapshot(in, debug, new HollowFilterConfig(true));
    }

    /**
     * Initialize the file engine using a snapshot from the provided RandomAccessFile.
     * <p>
     * Apply the provided {@link HollowFilterConfig} to the state.
     *
     * @param in the Hollow blob input to read the snapshot from
     * @param filter the filtering configuration to filter the snapshot
     * @throws IOException if the snapshot could not be read
     */
    public void readSnapshot(HollowBlobInput in, BufferedWriter debug, HollowFilterConfig filter) throws IOException {
        HollowBlobHeader header = readHeader(in, false);

        notifyBeginUpdate();

        long startTime = System.currentTimeMillis();

        int numStates = VarInt.readVInt(in);

        Collection<String> typeNames = new TreeSet<>();
        for(int i=0;i<numStates;i++) {
            String typeName = readTypeFileSnapshot(in, debug, header, filter);
            typeNames.add(typeName);
        }

        stateEngine.wireTypeStatesToSchemas();

        long endTime = System.currentTimeMillis();

        log.info("mmap'ed SNAPSHOT COMPLETED IN " + (endTime - startTime) + "ms");
        log.info("mmap'ed TYPES: " + typeNames);

        notifyEndUpdate();

        stateEngine.afterInitialization();
    }

    /**
     * Update the state engine using a delta (or reverse delta) blob from the provided HollowBlobInput.
     * <p>
     * If a {@link HollowFilterConfig} was applied at the time the {@link HollowReadStateEngine} was initialized
     * with a snapshot, it will continue to be in effect after the state is updated.
     *
     * @param in the Hollow blob input to read the delta from
     * @throws IOException if the delta could not be applied
     */
    public void applyDelta(HollowBlobInput in, BufferedWriter debug) throws IOException {
        HollowBlobHeader header = readHeader(in, true);
        notifyBeginUpdate();

        long startTime = System.currentTimeMillis();

        int numStates = VarInt.readVInt(in);

        Collection<String> typeNames = new TreeSet<String>();
        for(int i=0;i<numStates;i++) {
            String typeName = readTypeStateDelta(in, debug, header);
            typeNames.add(typeName);
            stateEngine.getMemoryRecycler().swap();
        }

        long endTime = System.currentTimeMillis();

        log.info("DELTA COMPLETED IN " + (endTime - startTime) + "ms");
        log.info("TYPES: " + typeNames);

        notifyEndUpdate();

    }

    private HollowBlobHeader readHeader(HollowBlobInput f, boolean isDelta) throws IOException {
        HollowBlobHeader header = headerReader.readHeader(f);

        if(isDelta && header.getOriginRandomizedTag() != stateEngine.getCurrentRandomizedTag())
            throw new IOException("Attempting to apply a delta to a state from which it was not originated!");

        stateEngine.setCurrentRandomizedTag(header.getDestinationRandomizedTag());
        stateEngine.setHeaderTags(header.getHeaderTags());
        return header;
    }

    private void notifyBeginUpdate() {
        for(HollowTypeReadState typeFile: stateEngine.getTypeStates()) {
            for(HollowTypeStateListener listener : typeFile.getListeners()) {
                listener.beginUpdate();
            }
        }
    }

    private void notifyEndUpdate() {
        for(HollowTypeReadState typeFile : stateEngine.getTypeStates()) {
            for(HollowTypeStateListener listener : typeFile.getListeners()) {
                listener.endUpdate();
            }
        }
    }

    private String readTypeFileSnapshot(HollowBlobInput in, BufferedWriter debug, HollowBlobHeader header, HollowFilterConfig filter) throws IOException {
        HollowSchema schema = HollowSchema.readFrom(in);

        int numShards = readNumShards(in);

        if(schema instanceof HollowObjectSchema) {
            if(!filter.doesIncludeType(schema.getName())) {
                HollowObjectTypeReadState.discardSnapshot(in, (HollowObjectSchema)schema, numShards);
            } else {
                HollowObjectSchema unfilteredSchema = (HollowObjectSchema)schema;
                HollowObjectSchema filteredSchema = unfilteredSchema.filterSchema(filter);
                populateTypeStateSnapshot(in, debug, new HollowObjectTypeReadState(stateEngine, filteredSchema, unfilteredSchema, numShards));
            }
        } else if (schema instanceof HollowListSchema) {
            if(!filter.doesIncludeType(schema.getName())) {
                HollowListTypeReadState.discardSnapshot(in, numShards);
            } else {
                populateTypeStateSnapshot(in, debug, new HollowListTypeReadState(stateEngine, (HollowListSchema)schema, numShards));
            }
        } else if(schema instanceof HollowSetSchema) {
            if(!filter.doesIncludeType(schema.getName())) {
                HollowSetTypeReadState.discardSnapshot(in, numShards);
            } else {
                populateTypeStateSnapshot(in, debug, new HollowSetTypeReadState(stateEngine, (HollowSetSchema)schema, numShards));
            }
        } else if(schema instanceof HollowMapSchema) {
            if(!filter.doesIncludeType(schema.getName())) {
                HollowMapTypeReadState.discardSnapshot(in, numShards);
            } else {
                populateTypeStateSnapshot(in, debug, new HollowMapTypeReadState(stateEngine, (HollowMapSchema)schema, numShards));
            }
        }

        return schema.getName();
    }

    private void populateTypeStateSnapshot(HollowBlobInput in, BufferedWriter debug, HollowTypeReadState typeState) throws IOException {
        stateEngine.addTypeState(typeState);
        typeState.readSnapshot(in, debug, stateEngine.getMemoryRecycler());
    }

    private String readTypeStateDelta(HollowBlobInput in, BufferedWriter debug, HollowBlobHeader header) throws IOException {
        HollowSchema schema = HollowSchema.readFrom(in);

        int numShards = readNumShards(in);

        HollowTypeReadState typeState = stateEngine.getTypeState(schema.getName());
        if(typeState != null) {
            typeState.applyDelta(in, debug, schema, stateEngine.getMemoryRecycler());
        } else {
            discardDelta(in, schema, numShards);
        }

        return schema.getName();
    }

    private int readNumShards(HollowBlobInput in) throws IOException {
        int backwardsCompatibilityBytes = VarInt.readVInt(in);

        if(backwardsCompatibilityBytes == 0)
            return 1;  /// produced by a version of hollow prior to 2.1.0, always only 1 shard.

        skipForwardsCompatibilityBytes(in);

        return VarInt.readVInt(in);
    }
        
    private void skipForwardsCompatibilityBytes(HollowBlobInput in) throws IOException {
        int bytesToSkip = VarInt.readVInt(in);
        while(bytesToSkip > 0) {
            int skippedBytes = (int)in.skipBytes(bytesToSkip);
            if(skippedBytes < 0)
                throw new EOFException();
            bytesToSkip -= skippedBytes;
        }
    }


    private void discardDelta(HollowBlobInput in, HollowSchema schema, int numShards) throws IOException {
        if(schema instanceof HollowObjectSchema)
            HollowObjectTypeReadState.discardDelta(in, (HollowObjectSchema)schema, numShards);
        else if(schema instanceof HollowListSchema)
            HollowListTypeReadState.discardDelta(in, numShards);
        else if(schema instanceof HollowSetSchema)
            HollowSetTypeReadState.discardDelta(in, numShards);
        else if(schema instanceof HollowMapSchema)
            HollowMapTypeReadState.discardDelta(in, numShards);
    }

}

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.orc;

import com.facebook.presto.memory.context.AggregatedMemoryContext;
import com.facebook.presto.orc.metadata.MetadataReader;
import com.facebook.presto.orc.metadata.OrcType;
import com.facebook.presto.orc.metadata.PostScript;
import com.facebook.presto.orc.metadata.StripeInformation;
import com.facebook.presto.orc.metadata.statistics.ColumnStatistics;
import com.facebook.presto.orc.metadata.statistics.StripeStatistics;
import com.facebook.presto.orc.reader.SelectiveStreamReader;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.Subfield;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockLease;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Closer;
import io.airlift.slice.Slice;
import io.airlift.units.DataSize;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.orc.reader.SelectiveStreamReaders.createStreamReader;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

public class OrcSelectiveRecordReader
        extends AbstractOrcRecordReader<SelectiveStreamReader>
{
    private final List<Integer> outputColumns;                        // elements are hive column indices
    private final List<FilterFunction> filterFunctions;
    private final Map<Integer, Integer> filterFunctionInputMapping;   // channel-to-hiveColumnIndex mapping
    private final Set<Integer> filterFunctionInputs;                  // channels
    private final Set<Integer> columnsWithFilters;                    // elements are hive column indices

    // Optimal order of stream readers
    private int[] streamReaderOrder;                                  // elements are hive column indices

    // An immutable list of initial positions; includes all positions: 0,1,2,3,4,..
    // This array may grow, but cannot shrink. The values don't change.
    private int[] positions;

    // Used in applyFilterFunctions; mutable
    private int[] outputPositions;
    private RuntimeException[] errors;

    public OrcSelectiveRecordReader(
            Map<Integer, Type> includedColumns,                 // key: hiveColumnIndex
            List<Integer> outputColumns,                        // elements are hive column indices
            Map<Integer, TupleDomainFilter> filters,            // key: hiveColumnIndex
            List<FilterFunction> filterFunctions,
            Map<Integer, Integer> filterFunctionInputMapping,   // channel-to-hiveColumnIndex mapping for all filter function inputs
            Map<Integer, List<Subfield>> requiredSubfields,     // key: hiveColumnIndex
            Map<Integer, Object> constantValues,                // key: hiveColumnIndex
            OrcPredicate predicate,
            long numberOfRows,
            List<StripeInformation> fileStripes,
            List<ColumnStatistics> fileStats,
            List<StripeStatistics> stripeStats,
            OrcDataSource orcDataSource,
            long offset,
            long length,
            List<OrcType> types,
            Optional<OrcDecompressor> decompressor,
            int rowsInRowGroup,
            DateTimeZone hiveStorageTimeZone,
            PostScript.HiveWriterVersion hiveWriterVersion,
            MetadataReader metadataReader,
            DataSize maxMergeDistance,
            DataSize tinyStripeThreshold,
            DataSize maxBlockSize,
            Map<String, Slice> userMetadata,
            AggregatedMemoryContext systemMemoryUsage,
            Optional<OrcWriteValidation> writeValidation,
            int initialBatchSize)
    {
        super(includedColumns,
                createStreamReaders(
                        orcDataSource,
                        types,
                        hiveStorageTimeZone,
                        includedColumns,
                        outputColumns,
                        filters,
                        filterFunctions,
                        filterFunctionInputMapping,
                        requiredSubfields,
                        systemMemoryUsage.newAggregatedMemoryContext()),
                predicate,
                numberOfRows,
                fileStripes,
                fileStats,
                stripeStats,
                orcDataSource,
                offset,
                length,
                types,
                decompressor,
                rowsInRowGroup,
                hiveStorageTimeZone,
                hiveWriterVersion,
                metadataReader,
                maxMergeDistance,
                tinyStripeThreshold,
                maxBlockSize,
                userMetadata,
                systemMemoryUsage,
                writeValidation,
                initialBatchSize);

        this.outputColumns = outputColumns;
        this.filterFunctions = filterFunctions;
        this.filterFunctionInputMapping = filterFunctionInputMapping;
        filterFunctionInputs = filterFunctions.stream()
                .flatMapToInt(function -> Arrays.stream(function.getInputChannels()))
                .boxed()
                .map(filterFunctionInputMapping::get)
                .collect(toImmutableSet());

        // Initial order of stream readers is:
        //  - readers with simple filters
        //  - followed by readers for columns that provide input to filter functions
        //  - followed by readers for columns that doesn't have any filtering
        streamReaderOrder = orderStreamReaders(includedColumns.keySet(), filters.keySet(), filterFunctionInputs);
        columnsWithFilters = filters.keySet();
    }

    private static int[] orderStreamReaders(Collection<Integer> columnIndices, Set<Integer> columnsWithFilters, Set<Integer> filterFunctionInputs)
    {
        int[] order = new int[columnIndices.size()];
        int i = 0;
        for (int columnIndex : columnsWithFilters) {
            order[i++] = columnIndex;
        }
        for (int columnIndex : filterFunctionInputs) {
            if (!columnsWithFilters.contains(columnIndex)) {
                order[i++] = columnIndex;
            }
        }
        for (int columnIndex : columnIndices) {
            if (!columnsWithFilters.contains(columnIndex) && !filterFunctionInputs.contains(columnIndex)) {
                order[i++] = columnIndex;
            }
        }

        return order;
    }

    private static SelectiveStreamReader[] createStreamReaders(
            OrcDataSource orcDataSource,
            List<OrcType> types,
            DateTimeZone hiveStorageTimeZone,
            Map<Integer, Type> includedColumns,
            List<Integer> outputColumns,
            Map<Integer, TupleDomainFilter> filters,
            List<FilterFunction> filterFunctions,
            Map<Integer, Integer> filterFunctionInputMapping,
            Map<Integer, List<Subfield>> requiredSubfields,
            AggregatedMemoryContext systemMemoryContext)
    {
        List<StreamDescriptor> streamDescriptors = createStreamDescriptor("", "", 0, types, orcDataSource).getNestedStreams();

        requireNonNull(filterFunctions, "filterFunctions is null");
        requireNonNull(filterFunctionInputMapping, "filterFunctionInputMapping is null");

        Set<Integer> filterFunctionInputColumns = filterFunctions.stream()
                .flatMapToInt(function -> Arrays.stream(function.getInputChannels()))
                .boxed()
                .map(filterFunctionInputMapping::get)
                .collect(toImmutableSet());

        OrcType rowType = types.get(0);
        SelectiveStreamReader[] streamReaders = new SelectiveStreamReader[rowType.getFieldCount()];
        for (int columnId = 0; columnId < rowType.getFieldCount(); columnId++) {
            if (includedColumns.containsKey(columnId)) {
                StreamDescriptor streamDescriptor = streamDescriptors.get(columnId);
                boolean outputRequired = outputColumns.contains(columnId) || filterFunctionInputColumns.contains(columnId);
                streamReaders[columnId] = createStreamReader(
                        streamDescriptor,
                        Optional.ofNullable(filters.get(columnId)),
                        outputRequired ? Optional.of(includedColumns.get(columnId)) : Optional.empty(),
                        Optional.ofNullable(requiredSubfields.get(columnId)).orElse(ImmutableList.of()),
                        hiveStorageTimeZone,
                        systemMemoryContext);
            }
        }
        return streamReaders;
    }

    public Page getNextPage()
            throws IOException
    {
        int batchSize = prepareNextBatch();
        if (batchSize < 0) {
            return null;
        }

        initializePositions(batchSize);

        int[] positionsToRead = this.positions;
        int positionCount = batchSize;
        boolean filterFunctionsApplied = filterFunctions.isEmpty();
        for (int columnIndex : streamReaderOrder) {
            if (!filterFunctionsApplied && !hasAnyFilter(columnIndex)) {
                positionCount = applyFilterFunctions(positionsToRead, positionCount);
                if (positionCount == 0) {
                    break;
                }

                positionsToRead = outputPositions;
                filterFunctionsApplied = true;
            }

            SelectiveStreamReader streamReader = getStreamReaders()[columnIndex];
            positionCount = streamReader.read(getNextRowInGroup(), positionsToRead, positionCount);
            if (positionCount == 0) {
                break;
            }

            positionsToRead = streamReader.getReadPositions();
        }

        if (positionCount > 0 && !filterFunctionsApplied) {
            positionCount = applyFilterFunctions(positionsToRead, positionCount);
            positionsToRead = outputPositions;
        }

        batchRead(batchSize);

        if (positionCount == 0) {
            return new Page(0);
        }

        Block[] blocks = new Block[outputColumns.size()];
        for (int i = 0; i < outputColumns.size(); i++) {
            int columnIndex = outputColumns.get(i);
            Block block = getStreamReaders()[columnIndex].getBlock(positionsToRead, positionCount);
            updateMaxCombinedBytesPerRow(columnIndex, block);
            blocks[i] = block;
        }

        Page page = new Page(positionCount, blocks);

        validateWritePageChecksum(page);

        return page;
    }

    private boolean hasAnyFilter(int columnIndex)
    {
        return columnsWithFilters.contains(columnIndex) || filterFunctionInputs.contains(columnIndex);
    }

    private void initializePositions(int batchSize)
    {
        if (positions == null || positions.length < batchSize) {
            positions = new int[batchSize];
            for (int i = 0; i < batchSize; i++) {
                positions[i] = i;
            }
        }
    }

    private int applyFilterFunctions(int[] positions, int positionCount)
    {
        BlockLease[] blockLeases = new BlockLease[getStreamReaders().length];
        Block[] blocks = new Block[getStreamReaders().length];
        for (int columnIndex : filterFunctionInputs) {
            blockLeases[columnIndex] = getStreamReaders()[columnIndex].getBlockView(positions, positionCount);
            blocks[columnIndex] = blockLeases[columnIndex].get();
        }

        try {
            initializeOutputPositions(positionCount);

            for (FilterFunction function : filterFunctions) {
                int[] inputs = function.getInputChannels();
                Block[] inputBlocks = new Block[inputs.length];

                for (int i = 0; i < inputs.length; i++) {
                    inputBlocks[i] = blocks[filterFunctionInputMapping.get(inputs[i])];
                }

                Page page = new Page(positionCount, inputBlocks);
                positionCount = function.filter(page, outputPositions, positionCount, errors);
                if (positionCount == 0) {
                    break;
                }
            }

            for (int i = 0; i < positionCount; i++) {
                if (errors[i] != null) {
                    throw errors[i];
                }
            }

            // at this point outputPositions are relative to page, e.g. they are indices into positions array
            // translate outputPositions to positions relative to the start of the row group,
            // e.g. make outputPositions a subset of positions array
            for (int i = 0; i < positionCount; i++) {
                outputPositions[i] = positions[outputPositions[i]];
            }
            return positionCount;
        }
        finally {
            for (BlockLease blockLease : blockLeases) {
                if (blockLease != null) {
                    blockLease.close();
                }
            }
        }
    }

    private void initializeOutputPositions(int positionCount)
    {
        if (outputPositions == null || outputPositions.length < positionCount) {
            outputPositions = new int[positionCount];
        }

        for (int i = 0; i < positionCount; i++) {
            outputPositions[i] = i;
        }

        if (errors == null || errors.length < positionCount) {
            errors = new RuntimeException[positionCount];
        }
        else {
            Arrays.fill(errors, null);
        }
    }

    @Override
    public void close()
            throws IOException
    {
        try (Closer closer = Closer.create()) {
            for (SelectiveStreamReader streamReader : getStreamReaders()) {
                if (streamReader != null) {
                    closer.register(streamReader::close);
                }
            }
        }

        super.close();
    }
}

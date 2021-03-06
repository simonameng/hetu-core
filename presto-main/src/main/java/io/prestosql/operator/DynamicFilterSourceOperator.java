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
package io.prestosql.operator;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import io.airlift.log.Logger;
import io.airlift.node.NodeInfo;
import io.airlift.units.DataSize;
import io.prestosql.operator.aggregation.TypedSet;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.dynamicfilter.DynamicFilter;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.predicate.ValueSet;
import io.prestosql.spi.statestore.StateCollection;
import io.prestosql.spi.statestore.StateMap;
import io.prestosql.spi.statestore.StateSet;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.planner.plan.PlanNodeId;
import io.prestosql.statestore.StateStoreProvider;
import io.prestosql.utils.DynamicFilterUtils;

import javax.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static io.prestosql.spi.type.TypeUtils.readNativeValue;
import static io.prestosql.spi.type.TypeUtils.readNativeValueForDynamicFilter;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

/**
 * This operator acts as a simple "pass-through" pipe, while saving its input pages.
 * The collected pages' value are used for creating a run-time filtering constraint (for probe-side table scan in an inner join).
 * We support only small build-side pages (which should be the case when using "broadcast" join).
 */
public class DynamicFilterSourceOperator
        implements Operator
{
    private static final int EXPECTED_BLOCK_BUILDER_SIZE = 8;
    private static final int DEFAULT_DYNAMIC_FILTER_SIZE = 1024 * 1024;
    public static final Logger log = Logger.get(DynamicFilterSourceOperator.class);

    public static class Channel
    {
        private final String filterId;
        String queryId;
        private final Type type;
        private final int index;

        public Channel(String filterId, Type type, int index, String queryId)
        {
            this.filterId = filterId;
            this.type = type;
            this.index = index;
            this.queryId = queryId;
        }
    }

    public static class DynamicFilterSourceOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final Consumer<TupleDomain<String>> dynamicPredicateConsumer;
        private final List<Channel> channels;
        private final int maxFilterPositionsCount;
        private final DataSize maxFilterSize;
        private final int dynamicFilterDataStructure;

        private boolean closed;

        private final DynamicFilter.Type filterType;
        private final StateStoreProvider stateStoreProvider;
        private final NodeInfo nodeInfo;

        /**
         * Constructor for the Dynamic Filter Source Operator Factory
         */
        public DynamicFilterSourceOperatorFactory(
                int operatorId,
                PlanNodeId planNodeId,
                Consumer<TupleDomain<String>> dynamicPredicateConsumer,
                List<Channel> channels,
                int maxFilterPositionsCount,
                DataSize maxFilterSize,
                int dynamicFilteringDataStructure,
                DynamicFilter.Type filterType,
                NodeInfo nodeInfo, StateStoreProvider stateStoreProvider)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.dynamicPredicateConsumer = requireNonNull(dynamicPredicateConsumer, "dynamicPredicateConsumer is null");
            this.channels = requireNonNull(channels, "channels is null");
            verify(channels.stream().map(channel -> channel.filterId).collect(toSet()).size() == channels.size(),
                    "duplicate dynamic filters are not allowed");
            verify(channels.stream().map(channel -> channel.index).collect(toSet()).size() == channels.size(),
                    "duplicate channel indices are not allowed");
            this.maxFilterPositionsCount = maxFilterPositionsCount;
            this.maxFilterSize = maxFilterSize;
            this.dynamicFilterDataStructure = dynamicFilteringDataStructure;
            this.filterType = filterType;
            this.nodeInfo = nodeInfo;
            this.stateStoreProvider = stateStoreProvider;
        }

        /**
         * creating the Dynamic Filter Source Operator using the driver context
         * @param driverContext
         * @return Operator
         */
        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            return new DynamicFilterSourceOperator(
                    driverContext.addOperatorContext(operatorId, planNodeId, DynamicFilterSourceOperator.class.getSimpleName()),
                    dynamicPredicateConsumer,
                    channels,
                    planNodeId,
                    maxFilterPositionsCount,
                    maxFilterSize,
                    dynamicFilterDataStructure,
                    filterType,
                    nodeInfo,
                    stateStoreProvider);
        }

        /**
         * No More Operators, function override
         */
        @Override
        public void noMoreOperators()
        {
            checkState(!closed, "Factory is already closed");
            closed = true;
        }

        /**
         * Duplicating the Dynamic Filter Source Operator
         */
        @Override
        public OperatorFactory duplicate()
        {
            throw new UnsupportedOperationException("duplicate() is not supported for DynamicFilterSourceOperatorFactory");
        }
    }

    private final OperatorContext context;
    private boolean finished;
    private Page current;
    private final Consumer<TupleDomain<String>> dynamicPredicateConsumer;
    private final int maxFilterPositionsCount;
    private final long maxFilterSizeInBytes;
    private final int dynamicFilterDataStructure;

    private final List<Channel> channels;

    // May be dropped if the predicate becomes too large.
    @Nullable
    private BlockBuilder[] blockBuilders;
    @Nullable
    private TypedSet[] valueSets;

    private final DynamicFilter.Type filterType;
    private final NodeInfo nodeInfo;
    private final StateStoreProvider stateStoreProvider;
    private long driverId;
    private boolean haveRegistered;
    private Set<String>[] stringValueSets;

    /**
     * Constructor for the Dynamic Filter Source Operator
     */
    // TODO: no need to collect dynamic filter if it's cached
    public DynamicFilterSourceOperator(
            OperatorContext context,
            Consumer<TupleDomain<String>> dynamicPredicateConsumer,
            List<Channel> channels,
            PlanNodeId planNodeId,
            int maxFilterPositionsCount,
            DataSize maxFilterSize,
            int dynamicFilterDataStructure,
            DynamicFilter.Type filterType,
            NodeInfo nodeInfo,
            StateStoreProvider stateStoreProvider)
    {
        this.context = requireNonNull(context, "context is null");
        this.maxFilterPositionsCount = maxFilterPositionsCount;
        this.maxFilterSizeInBytes = maxFilterSize.toBytes();
        this.dynamicFilterDataStructure = dynamicFilterDataStructure;

        this.dynamicPredicateConsumer = requireNonNull(dynamicPredicateConsumer, "dynamicPredicateConsumer is null");
        this.channels = requireNonNull(channels, "channels is null");
        this.nodeInfo = nodeInfo;

        this.blockBuilders = new BlockBuilder[channels.size()];
        this.valueSets = new TypedSet[channels.size()];
        this.stringValueSets = new Set[channels.size()];
        for (int i = 0; i < stringValueSets.length; i++) {
            stringValueSets[i] = new HashSet<>();
        }

        for (int channelIndex = 0; channelIndex < channels.size(); ++channelIndex) {
            Type type = channels.get(channelIndex).type;
            this.blockBuilders[channelIndex] = type.createBlockBuilder(null, EXPECTED_BLOCK_BUILDER_SIZE);
            this.valueSets[channelIndex] = new TypedSet(
                    type,
                    Optional.empty(),
                    blockBuilders[channelIndex],
                    EXPECTED_BLOCK_BUILDER_SIZE,
                    String.format("DynamicFilterSourceOperator_%s_%d", planNodeId, channelIndex),
                    Optional.empty() /* maxBlockMemory */);
        }
        this.stateStoreProvider = stateStoreProvider;
        this.filterType = filterType;

        if (stateStoreProvider != null && stateStoreProvider.getStateStore() != null) {
            driverId = stateStoreProvider.getStateStore().generateId();
            registerDynamicFilterTask(context.getSession().getQueryId().toString());
        }
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return context;
    }

    @Override
    public boolean needsInput()
    {
        return current == null && !finished;
    }

    @Override
    public void addInput(Page page)
    {
        verify(!finished, "DynamicFilterSourceOperator: addInput() may not be called after finish()");
        current = page;
        if (valueSets == null) {
            return;  // the predicate became too large.
        }

        // TODO: we should account for the memory used for collecting build-side values using MemoryContext
        long filterSizeInBytes = 0;
        int filterPositionsCount = 0;
        // Collect only the columns which are relevant for the JOIN.
        outer:
        for (int channelIndex = 0; channelIndex < channels.size(); ++channelIndex) {
            Block block = page.getBlock(channels.get(channelIndex).index);
            TypedSet valueSet = valueSets[channelIndex];
            Type columnType = channels.get(channelIndex).type;
            for (int position = 0; position < block.getPositionCount(); ++position) {
                String value = readNativeValueForDynamicFilter(columnType, block, position);
                if (value == null) {
                    handleTooLargePredicate();  // TODO: 1/7/20 rename this method as reset bloom filter etc
                    break outer;
                }
                stringValueSets[channelIndex].add(value);
                if (filterType == DynamicFilter.Type.LOCAL) {
                    valueSet.add(block, position);
                }
            }

            if (filterType == DynamicFilter.Type.LOCAL) {
                filterSizeInBytes += valueSet.getRetainedSizeInBytes();
                filterPositionsCount += valueSet.size();
            }
            else {
                filterPositionsCount += stringValueSets[channelIndex].size();
            }
        }
        if (filterPositionsCount > maxFilterPositionsCount || filterSizeInBytes > maxFilterSizeInBytes) {
            // The whole filter (summed over all columns) contains too much values or exceeds maxFilterSizeInBytes.
            //log.info("filter positions count too large");
            handleTooLargePredicate();
        }
    }

    private void handleTooLargePredicate()
    {
        stringValueSets = null;
        // The resulting predicate is too large, allow all probe-side values to be read.
        dynamicPredicateConsumer.accept(TupleDomain.all());

        // Drop references to collected values.
        valueSets = null;
        blockBuilders = null;
    }

    @Override
    public Page getOutput()
    {
        Page result = current;
        current = null;
        return result;
    }

    @Override
    public void finish()
    {
        if (finished) {
            // NOTE: finish() may be called multiple times (see comment at Driver::processInternal).
            return;
        }
        finished = true;
        if (valueSets == null) {
            return; // the predicate became too large.
        }

        finishDynamicFilterTask();
        if (filterType == DynamicFilter.Type.GLOBAL) {
            valueSets = null;
            blockBuilders = null;
            dynamicPredicateConsumer.accept(TupleDomain.all());
            return;
        }

        ImmutableMap.Builder<String, Domain> domainsBuilder = new ImmutableMap.Builder<>();
        if (filterType != DynamicFilter.Type.GLOBAL) {
            for (int channelIndex = 0; channelIndex < channels.size(); ++channelIndex) {
                Block block = blockBuilders[channelIndex].build();
                Type type = channels.get(channelIndex).type;
                domainsBuilder.put(channels.get(channelIndex).filterId, convertToDomain(type, block));
            }
        }
        valueSets = null;
        blockBuilders = null;
        dynamicPredicateConsumer.accept(TupleDomain.withColumnDomains(domainsBuilder.build()));
    }

    private Domain convertToDomain(Type type, Block block)
    {
        ImmutableList.Builder<Object> values = ImmutableList.builder();
        for (int position = 0; position < block.getPositionCount(); ++position) {
            Object value = readNativeValue(type, block, position);
            if (value != null) {
                values.add(value);
            }
        }
        // Inner and right join doesn't match rows with null key column values.
        return Domain.create(ValueSet.copyOf(type, values.build()), false);
    }

    @Override
    public boolean isFinished()
    {
        return current == null && finished;
    }

    private void registerDynamicFilterTask(String queryId)
    {
        for (Channel channel : channels) {
            try {
                stateStoreProvider.getStateStore().createStateCollection(DynamicFilterUtils.createKey(DynamicFilterUtils.REGISTERPREFIX, channel.filterId, queryId), StateCollection.Type.SET);
                stateStoreProvider.getStateStore().createStateCollection(DynamicFilterUtils.createKey(DynamicFilterUtils.WORKERSPREFIX, channel.filterId, queryId), StateCollection.Type.SET);
                stateStoreProvider.getStateStore().createStateCollection(DynamicFilterUtils.createKey(DynamicFilterUtils.PARTIALPREFIX, channel.filterId, queryId), StateCollection.Type.SET);
                stateStoreProvider.getStateStore().createStateCollection(DynamicFilterUtils.createKey(DynamicFilterUtils.FINISHREFIX, channel.filterId, queryId), StateCollection.Type.SET);
                stateStoreProvider.getStateStore().createStateCollection(DynamicFilterUtils.DFTYPEMAP, StateCollection.Type.MAP);
                ((StateSet) stateStoreProvider.getStateStore().getStateCollection(DynamicFilterUtils.createKey(DynamicFilterUtils.REGISTERPREFIX, channel.filterId, queryId))).add(driverId);
                this.haveRegistered = true;
            }
            catch (Exception e) {
                log.error("could not register dynamic filter, Exception happened:" + e.getMessage());
            }
        }
    }

    private void finishDynamicFilterTask()
    {
        if (!haveRegistered) {
            return;
        }
        for (int channelIndex = 0; channelIndex < channels.size(); ++channelIndex) {
            Channel channel = channels.get(channelIndex);
            String id = channel.filterId;
            String key = DynamicFilterUtils.createKey(DynamicFilterUtils.PARTIALPREFIX, id, channel.queryId);
            String typeKey = DynamicFilterUtils.createKey(DynamicFilterUtils.TYPEPREFIX, id, channel.queryId);

            String dynamicFilterType = "";
            dynamicFilterType = getSetType(typeKey);

            if (dynamicFilterType.equals(DynamicFilterUtils.BLOOMFILTERTYPEGLOBAL) || dynamicFilterType.equals(DynamicFilterUtils.BLOOMFILTERTYPELOCAL)) {
                log.debug("creating new bloomfilter dynamic filter for size of: " + stringValueSets[channelIndex].size() + key + "     " + driverId);
                byte[] finalOutput = createBloomFilter(stringValueSets[channelIndex]);
                if (finalOutput != null) {
                    ((StateSet) stateStoreProvider.getStateStore().getStateCollection(key)).add(finalOutput);
                }
            }
            else {
                log.debug("creating new string set dynamic filter for size of: " + stringValueSets[channelIndex].size() + key + "     " + driverId);
                ((StateSet) stateStoreProvider.getStateStore().getStateCollection(key))
                        .add(stringValueSets[channelIndex]);
            }
            ((StateSet) stateStoreProvider.getStateStore().getStateCollection(DynamicFilterUtils.createKey(DynamicFilterUtils.FINISHREFIX, channel.filterId, channel.queryId))).add(driverId);
            ((StateSet) stateStoreProvider.getStateStore().getStateCollection(DynamicFilterUtils.createKey(DynamicFilterUtils.WORKERSPREFIX, channel.filterId, channel.queryId))).add(nodeInfo.getNodeId());
        }
    }

    public byte[] createBloomFilter(Set<String> stringValueSet)
    {
        byte[] finalOutput = null;
        BloomFilter bloomFilter = BloomFilter.create(Funnels.stringFunnel(Charset.defaultCharset()), DEFAULT_DYNAMIC_FILTER_SIZE, 0.1);
        for (String value : stringValueSet) {
            bloomFilter.put(value);
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            bloomFilter.writeTo(out);
            finalOutput = out.toByteArray();
        }
        catch (IOException e) {
            log.error("could not  finish filter, Exception happened:" + e.getMessage());
        }
        return finalOutput;
    }

    public String getSetType(String key)
    {
        String type = DynamicFilterUtils.BLOOMFILTERTYPEGLOBAL;
        if (filterType == DynamicFilter.Type.LOCAL) {
            type = DynamicFilterUtils.BLOOMFILTERTYPELOCAL;
        }
        if (dynamicFilterDataStructure == 1) {
            type = DynamicFilterUtils.HASHSETTYPEGLOBAL;
            if (filterType == DynamicFilter.Type.LOCAL) {
                type = DynamicFilterUtils.HASHSETTYPELOCAL;
            }
        }
        ((StateMap) stateStoreProvider.getStateStore()
                .getStateCollection(DynamicFilterUtils.DFTYPEMAP)).put(key, type);
        return type;
    }
}

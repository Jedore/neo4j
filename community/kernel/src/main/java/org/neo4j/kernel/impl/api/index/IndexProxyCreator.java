/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.index;

import static org.neo4j.internal.schema.IndexType.LOOKUP;

import java.io.IOException;
import java.nio.file.OpenOption;
import org.eclipse.collections.api.set.ImmutableSet;
import org.neo4j.common.TokenNameLookup;
import org.neo4j.internal.kernel.api.IndexMonitor;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.io.memory.ByteBufferFactory;
import org.neo4j.kernel.api.index.IndexAccessor;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.api.index.MinimalIndexAccessor;
import org.neo4j.kernel.impl.api.index.stats.IndexStatisticsStore;
import org.neo4j.logging.InternalLogProvider;
import org.neo4j.memory.MemoryTracker;

/**
 * Helper class of {@link IndexingService}. Used mainly as factory of index proxies.
 */
class IndexProxyCreator {
    private final IndexSamplingConfig samplingConfig;
    private final IndexStatisticsStore indexStatisticsStore;
    private final IndexProviderMap providerMap;
    private final TokenNameLookup tokenNameLookup;
    private final InternalLogProvider logProvider;
    private final ImmutableSet<OpenOption> openOptions;

    IndexProxyCreator(
            IndexSamplingConfig samplingConfig,
            IndexStatisticsStore indexStatisticsStore,
            IndexProviderMap providerMap,
            TokenNameLookup tokenNameLookup,
            InternalLogProvider logProvider,
            ImmutableSet<OpenOption> openOptions) {
        this.samplingConfig = samplingConfig;
        this.indexStatisticsStore = indexStatisticsStore;
        this.providerMap = providerMap;
        this.tokenNameLookup = tokenNameLookup;
        this.logProvider = logProvider;
        this.openOptions = openOptions;
    }

    IndexProxy createPopulatingIndexProxy(
            IndexDescriptor index, boolean flipToTentative, IndexMonitor monitor, IndexPopulationJob populationJob) {
        final FlippableIndexProxy flipper = new FlippableIndexProxy();

        IndexPopulator populator = populatorFromProvider(
                index, samplingConfig, populationJob.bufferFactory(), populationJob.getMemoryTracker());

        IndexProxyStrategy indexProxyStrategy = createIndexProxyStrategy(index);
        FailedIndexProxyFactory failureDelegateFactory =
                new FailedPopulatingIndexProxyFactory(indexProxyStrategy, populator, logProvider);

        MultipleIndexPopulator.IndexPopulation indexPopulation =
                populationJob.addPopulator(populator, indexProxyStrategy, flipper, failureDelegateFactory);
        PopulatingIndexProxy populatingIndex = new PopulatingIndexProxy(index, populationJob, indexPopulation);

        flipper.flipTo(populatingIndex);

        // Prepare for flipping to online mode
        flipper.setFlipTarget(() -> {
            monitor.populationCompleteOn(index);
            IndexAccessor accessor = onlineAccessorFromProvider(index, samplingConfig);
            OnlineIndexProxy onlineProxy = new OnlineIndexProxy(indexProxyStrategy, accessor, true);
            if (flipToTentative) {
                // This TentativeConstraintIndexProxy will exist between flipping the index to online and the constraint
                // transaction
                // activating the index - which flips to a regular OnlineIndexProxy. The index is activated in the
                // BatchContext#close,
                // at the closing of the committing constraint transaction.
                // The index is added among those that need activation when updating the schemaRule with
                // owningConstraintId,
                // see IndexTransactionApplierFactory#processSchemaCommand in UPDATE.
                return new TentativeConstraintIndexProxy(flipper, onlineProxy);
            }
            return onlineProxy;
        });

        return new ContractCheckingIndexProxy(flipper);
    }

    IndexProxy createRecoveringIndexProxy(IndexDescriptor descriptor) {
        IndexProxy proxy = new RecoveringIndexProxy(descriptor);
        return new ContractCheckingIndexProxy(proxy);
    }

    IndexProxy createOnlineIndexProxy(IndexDescriptor descriptor) {
        try {
            IndexAccessor onlineAccessor = onlineAccessorFromProvider(descriptor, samplingConfig);
            IndexProxyStrategy indexProxyStrategy = createIndexProxyStrategy(descriptor);
            IndexProxy proxy = new OnlineIndexProxy(indexProxyStrategy, onlineAccessor, false);
            // it will be started later, when recovery is completed
            return new ContractCheckingIndexProxy(proxy);
        } catch (IOException | RuntimeException e) {
            logProvider
                    .getLog(getClass())
                    .error(
                            "Failed to open index: " + descriptor.getId() + " ("
                                    + descriptor.userDescription(tokenNameLookup) + "), requesting re-population.",
                            e);
            return createRecoveringIndexProxy(descriptor);
        }
    }

    private IndexProxyStrategy createIndexProxyStrategy(IndexDescriptor descriptor) {
        if (descriptor.getIndexType() == LOOKUP) {
            return new TokenIndexProxyStrategy(descriptor, tokenNameLookup);
        } else {
            return new ValueIndexProxyStrategy(descriptor, indexStatisticsStore, tokenNameLookup);
        }
    }

    IndexProxy createFailedIndexProxy(IndexDescriptor descriptor, IndexPopulationFailure populationFailure) {
        // Note about the buffer factory instantiation here. Question is why an index populator is instantiated for a
        // failed index proxy to begin with.
        // The byte buffer factory should not be used here anyway so the buffer size doesn't actually matter.
        MinimalIndexAccessor minimalIndexAccessor = minimalIndexAccessorFromProvider(descriptor);
        IndexProxyStrategy indexProxyStrategy = createIndexProxyStrategy(descriptor);
        IndexProxy proxy;
        proxy = new FailedIndexProxy(indexProxyStrategy, minimalIndexAccessor, populationFailure, logProvider);
        return new ContractCheckingIndexProxy(proxy);
    }

    private IndexPopulator populatorFromProvider(
            IndexDescriptor index,
            IndexSamplingConfig samplingConfig,
            ByteBufferFactory bufferFactory,
            MemoryTracker memoryTracker) {
        IndexProvider provider = providerMap.lookup(index.getIndexProvider());
        return provider.getPopulator(index, samplingConfig, bufferFactory, memoryTracker, tokenNameLookup, openOptions);
    }

    private MinimalIndexAccessor minimalIndexAccessorFromProvider(IndexDescriptor index) {
        IndexProvider provider = providerMap.lookup(index.getIndexProvider());
        return provider.getMinimalIndexAccessor(index);
    }

    private IndexAccessor onlineAccessorFromProvider(IndexDescriptor index, IndexSamplingConfig samplingConfig)
            throws IOException {
        IndexProvider provider = providerMap.lookup(index.getIndexProvider());
        return provider.getOnlineAccessor(index, samplingConfig, tokenNameLookup, openOptions);
    }
}

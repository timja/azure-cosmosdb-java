/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.microsoft.azure.cosmosdb.internal.routing;

import java.util.Collection;
import java.util.List;

import com.microsoft.azure.cosmosdb.PartitionKeyRange;
import org.apache.commons.lang3.tuple.ImmutablePair;

/**
 * Used internally in request routing in the Azure Cosmos DB database service.
 */
public interface CollectionRoutingMap<TPartitionInfo> {
    List<PartitionKeyRange> getOrderedPartitionKeyRanges();

    PartitionKeyRange getRangeByEffectivePartitionKey(String effectivePartitionKeyValue);

    PartitionKeyRange getRangeByPartitionKeyRangeId(String partitionKeyRangeId);

    Collection<PartitionKeyRange> getOverlappingRanges(Range<String> range);

    Collection<PartitionKeyRange> getOverlappingRanges(Collection<Range<String>> providedPartitionKeyRanges);

    PartitionKeyRange tryGetRangeByPartitionKeyRangeId(String partitionKeyRangeId);

    TPartitionInfo tryGetInfoByPartitionKeyRangeId(String partitionKeyRangeId);

    boolean IsGone(String partitionKeyRangeId);

    String getCollectionUniqueId();

    CollectionRoutingMap tryCombine(List<ImmutablePair<PartitionKeyRange, TPartitionInfo>> ranges);
}

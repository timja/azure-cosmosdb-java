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

package com.microsoft.azure.cosmosdb.internal;


import com.microsoft.azure.cosmosdb.DocumentClientException;
import com.microsoft.azure.cosmosdb.rx.internal.RMResources;
import com.microsoft.azure.cosmosdb.rx.internal.Strings;
import com.microsoft.azure.cosmosdb.rx.internal.Utils;
import org.apache.commons.collections4.map.UnmodifiableMap;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static com.microsoft.azure.cosmosdb.rx.internal.Utils.ValueHolder;

/**
 * Models vector clock bases session token. Session token has the following format:
 * {Version}#{GlobalLSN}#{RegionId1}={LocalLsn1}#{RegionId2}={LocalLsn2}....#{RegionIdN}={LocalLsnN}
 * 'Version' captures the configuration number of the partition which returned this session token.
 * 'Version' is incremented everytime topology of the partition is updated (say due to Add/Remove/Failover).
 * * The choice of separators '#' and '=' is important. Separators ';' and ',' are used to delimit
 * per-partitionKeyRange session token
 * session
 */
public class VectorSessionToken implements ISessionToken {
    private final static Logger logger = LoggerFactory.getLogger(VectorSessionToken.class);
    private final static char SegmentSeparator = '#';
    private final static char RegionProgressSeparator = '=';

    private final long version;
    private final long globalLsn;
    private final UnmodifiableMap<Integer, Long> localLsnByRegion;

    private String sessionToken;

    private VectorSessionToken(long version, long globalLsn, UnmodifiableMap<Integer, Long> localLsnByRegion) {
        this(version, globalLsn, localLsnByRegion, null);
    }

    private VectorSessionToken(long version, long globalLsn, UnmodifiableMap<Integer, Long> localLsnByRegion, String sessionToken) {
        this.version = version;
        this.globalLsn = globalLsn;
        this.localLsnByRegion = localLsnByRegion;
        this.sessionToken = sessionToken;
        if (this.sessionToken == null) {
            String regionProgress = String.join(
                    Character.toString(VectorSessionToken.SegmentSeparator),
                    localLsnByRegion.
                            entrySet()
                            .stream()
                            .map(kvp -> String.format("%s%s%s",
                                    kvp.getKey(), VectorSessionToken.RegionProgressSeparator, kvp.getValue()))
                            .collect(Collectors.toList()));

            if (Strings.isNullOrEmpty(regionProgress)) {
                this.sessionToken = String.format(
                        "%s%s%s",
                        this.version,
                        VectorSessionToken.SegmentSeparator,
                        this.globalLsn);
            } else {
                this.sessionToken = String.format(
                        "%s%s%s%s%s",
                        this.version,
                        VectorSessionToken.SegmentSeparator,
                        this.globalLsn,
                        VectorSessionToken.SegmentSeparator,
                        regionProgress);
            }
        }
    }

    public static boolean tryCreate(String sessionToken, ValueHolder<ISessionToken> parsedSessionToken) {
        ValueHolder<Long> versionHolder = ValueHolder.initialize(-1l);
        ValueHolder<Long> globalLsnHolder = ValueHolder.initialize(-1l);

        ValueHolder<UnmodifiableMap<Integer, Long>> localLsnByRegion = ValueHolder.initialize(null);

        if (VectorSessionToken.tryParseSessionToken(
                sessionToken,
                versionHolder,
                globalLsnHolder,
                localLsnByRegion)) {
            parsedSessionToken.v = new VectorSessionToken(versionHolder.v, globalLsnHolder.v, localLsnByRegion.v, sessionToken);
            return true;
        } else {
            return false;
        }
    }

    public long getLSN() {
        return this.globalLsn;
    }

    @Override
    public boolean equals(Object obj) {
        VectorSessionToken other = Utils.as(obj, VectorSessionToken.class);

        if (other == null) {
            return false;
        }

        return this.version == other.version
                && this.globalLsn == other.globalLsn
                && this.areRegionProgressEqual(other.localLsnByRegion);
    }

    public boolean isValid(ISessionToken otherSessionToken) throws DocumentClientException {
        VectorSessionToken other = Utils.as(otherSessionToken, VectorSessionToken.class);

        if (other == null) {
            throw new IllegalArgumentException("otherSessionToken");
        }

        if (other.version < this.version || other.globalLsn < this.globalLsn) {
            return false;
        }

        if (other.version == this.version && other.localLsnByRegion.size() != this.localLsnByRegion.size()) {
            throw new InternalServerErrorException(
                    String.format(RMResources.InvalidRegionsInSessionToken, this.sessionToken, other.sessionToken));
        }

        for (Map.Entry<Integer, Long> kvp : other.localLsnByRegion.entrySet()) {
            Integer regionId = kvp.getKey();
            long otherLocalLsn = kvp.getValue();
            ValueHolder<Long> localLsn = ValueHolder.initialize(-1l);


            if (!Utils.tryGetValue(this.localLsnByRegion, regionId, localLsn)) {
                // Region mismatch: other session token has progress for a region which is missing in this session token
                // Region mismatch can be ignored only if this session token version is smaller than other session token version
                if (this.version == other.version) {
                    throw new InternalServerErrorException(
                            String.format(RMResources.InvalidRegionsInSessionToken, this.sessionToken, other.sessionToken));
                } else {
                    // ignore missing region as other session token version > this session token version
                }
            } else {
                // region is present in both session tokens.
                if (otherLocalLsn < localLsn.v) {
                    return false;
                }
            }
        }

        return true;
    }

    public ISessionToken merge(ISessionToken obj) throws DocumentClientException {
        VectorSessionToken other = Utils.as(obj, VectorSessionToken.class);

        if (other == null) {
            throw new IllegalArgumentException("obj");
        }

        if (this.version == other.version && this.localLsnByRegion.size() != other.localLsnByRegion.size()) {
            throw new InternalServerErrorException(
                    String.format(RMResources.InvalidRegionsInSessionToken, this.sessionToken, other.sessionToken));
        }

        VectorSessionToken sessionTokenWithHigherVersion;
        VectorSessionToken sessionTokenWithLowerVersion;

        if (this.version < other.version) {
            sessionTokenWithLowerVersion = this;
            sessionTokenWithHigherVersion = other;
        } else {
            sessionTokenWithLowerVersion = other;
            sessionTokenWithHigherVersion = this;
        }

        Map<Integer, Long> highestLocalLsnByRegion = new HashMap<>();

        for (Map.Entry<Integer, Long> kvp : sessionTokenWithHigherVersion.localLsnByRegion.entrySet()) {
            Integer regionId = kvp.getKey();

            long localLsn1 = kvp.getValue();
            ValueHolder<Long> localLsn2 = ValueHolder.initialize(-1l);

            if (Utils.tryGetValue(sessionTokenWithLowerVersion.localLsnByRegion, regionId, localLsn2)) {
                highestLocalLsnByRegion.put(regionId, Math.max(localLsn1, localLsn2.v));
            } else if (this.version == other.version) {
                throw new InternalServerErrorException(
                        String.format(RMResources.InvalidRegionsInSessionToken, this.sessionToken, other.sessionToken));
            } else {
                highestLocalLsnByRegion.put(regionId, localLsn1);
            }
        }

        return new VectorSessionToken(
                Math.max(this.version, other.version),
                Math.max(this.globalLsn, other.globalLsn),
                (UnmodifiableMap) UnmodifiableMap.unmodifiableMap(highestLocalLsnByRegion));
    }

    public String convertToString() {
        return this.sessionToken;
    }

    private boolean areRegionProgressEqual(UnmodifiableMap<Integer, Long> other) {
        if (this.localLsnByRegion.size() != other.size()) {
            return false;
        }

        for (Map.Entry<Integer, Long> kvp : this.localLsnByRegion.entrySet()) {
            Integer regionId = kvp.getKey();
            ValueHolder<Long> localLsn1 = ValueHolder.initialize(kvp.getValue());
            ValueHolder<Long> localLsn2 = ValueHolder.initialize(-1l);

            if (Utils.tryGetValue(other, regionId, localLsn2)) {
                if (ObjectUtils.notEqual(localLsn1.v, localLsn2.v)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean tryParseSessionToken(
            String sessionToken,
            ValueHolder<Long> version,
            ValueHolder<Long> globalLsn,
            ValueHolder<UnmodifiableMap<Integer, Long>> localLsnByRegion) {
        version.v = 0L;
        localLsnByRegion.v = null;
        globalLsn.v = -1L;

        if (Strings.isNullOrEmpty(sessionToken)) {
            logger.warn("Session token is empty");
            return false;
        }

        String[] segments = StringUtils.split(sessionToken, VectorSessionToken.SegmentSeparator);

        if (segments.length < 2) {
            return false;
        }

        if (!tryParseLong(segments[0], version)
                || !tryParseLong(segments[1], globalLsn)) {
            logger.warn("Unexpected session token version number '{}' OR global lsn '{}'.", segments[0], segments[1]);
            return false;
        }

        Map<Integer, Long> lsnByRegion = new HashMap<>();

        for (int i = 2; i < segments.length; i++) {
            String regionSegment = segments[i];

            String[] regionIdWithLsn = StringUtils.split(regionSegment, VectorSessionToken.RegionProgressSeparator);

            if (regionIdWithLsn.length != 2) {
                logger.warn("Unexpected region progress segment length '{}' in session token.", regionIdWithLsn.length);
                return false;
            }

            ValueHolder<Integer> regionId = ValueHolder.initialize(0);
            ValueHolder<Long> localLsn = ValueHolder.initialize(-1l);

            if (!tryParseInt(regionIdWithLsn[0], regionId)
                    || !tryParseLong(regionIdWithLsn[1], localLsn)) {
                logger.warn("Unexpected region progress '{}' for region '{}' in session token.", regionIdWithLsn[0], regionIdWithLsn[1]);
                return false;
            }

            lsnByRegion.put(regionId.v, localLsn.v);
        }

        localLsnByRegion.v = (UnmodifiableMap) UnmodifiableMap.unmodifiableMap(lsnByRegion);
        return true;
    }

    private static boolean tryParseLong(String str, ValueHolder<Long> value) {
        try {
            value.v = Long.parseLong(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean tryParseInt(String str, ValueHolder<Integer> value) {
        try {
            value.v = Integer.parseInt(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

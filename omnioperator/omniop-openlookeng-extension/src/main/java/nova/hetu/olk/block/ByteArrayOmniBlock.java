/*
 * Copyright (C) 2020-2022. Huawei Technologies Co., Ltd. All rights reserved.
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

package nova.hetu.olk.block;

import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.block.ByteArrayBlockEncoding;
import io.prestosql.spi.util.BloomFilter;
import nova.hetu.omniruntime.vector.BooleanVec;
import nova.hetu.omniruntime.vector.Vec;
import nova.hetu.omniruntime.vector.VecAllocator;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.airlift.slice.SizeOf.sizeOf;
import static io.prestosql.spi.block.BlockUtil.checkArrayRange;
import static io.prestosql.spi.block.BlockUtil.checkValidRegion;
import static io.prestosql.spi.block.BlockUtil.compactArray;
import static io.prestosql.spi.block.BlockUtil.countUsedPositions;
import static nova.hetu.olk.tool.BlockUtils.compactVec;

/**
 * The type Byte array omni block.
 *
 * @since 20210630
 */
public class ByteArrayOmniBlock
        implements Block<Byte>
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(ByteArrayOmniBlock.class).instanceSize();

    private final VecAllocator vecAllocator;

    private final int arrayOffset;

    private final int positionCount;

    @Nullable
    private final byte[] valueIsNull;

    private final BooleanVec values;

    private final long sizeInBytes;

    private final long retainedSizeInBytes;

    /**
     * Instantiates a new Byte array omni block.
     *
     * @param vecAllocator the vector allocator
     * @param positionCount the position count
     * @param valueIsNull the value is null
     * @param values the values
     */
    public ByteArrayOmniBlock(VecAllocator vecAllocator, int positionCount, Optional<byte[]> valueIsNull,
                              byte[] values)
    {
        this(vecAllocator, 0, positionCount, valueIsNull.orElse(null), values);
    }

    /**
     * Instantiates a new Byte array omni block.
     *
     * @param positionCount the position count
     * @param values the values
     */
    public ByteArrayOmniBlock(int positionCount, BooleanVec values)
    {
        this(positionCount, values.hasNullValue() ? Optional.of(values.getRawValueNulls()) : Optional.empty(), values);
    }

    /**
     * Instantiates a new Byte array omni block.
     *
     * @param positionCount the position count
     * @param valueIsNull the value is null
     * @param values the values
     */
    public ByteArrayOmniBlock(int positionCount, Optional<byte[]> valueIsNull, BooleanVec values)
    {
        this(values.getOffset(), positionCount, valueIsNull.orElse(null), values);
    }

    /**
     * Instantiates a new Byte array omni block.
     *
     * @param vecAllocator the vector allocator
     * @param arrayOffset the array offset
     * @param positionCount the position count
     * @param valueIsNull the value is null
     * @param values the values
     */
    public ByteArrayOmniBlock(VecAllocator vecAllocator, int arrayOffset, int positionCount, byte[] valueIsNull,
                              byte[] values)
    {
        this.vecAllocator = vecAllocator;
        if (arrayOffset < 0) {
            throw new IllegalArgumentException("arrayOffset is negative");
        }

        if (positionCount < 0) {
            throw new IllegalArgumentException("positionCount is negative");
        }
        this.positionCount = positionCount;

        if (values.length - arrayOffset < positionCount) {
            throw new IllegalArgumentException("values length is less than positionCount");
        }

        this.values = new BooleanVec(vecAllocator, positionCount);
        this.values.put(values, 0, arrayOffset, positionCount);

        if (valueIsNull != null && valueIsNull.length - arrayOffset < positionCount) {
            throw new IllegalArgumentException("isNull length is less than positionCount");
        }

        if (valueIsNull != null) {
            this.values.setNulls(0, valueIsNull, arrayOffset, positionCount);
            this.valueIsNull = compactArray(valueIsNull, arrayOffset, positionCount);
        }
        else {
            this.valueIsNull = null;
        }

        this.arrayOffset = 0;

        sizeInBytes = (Byte.BYTES + Byte.BYTES) * (long) positionCount;
        retainedSizeInBytes = INSTANCE_SIZE + sizeOf(valueIsNull) + this.values.getCapacityInBytes();
    }

    /**
     * Instantiates a new Byte array omni block.
     *
     * @param arrayOffset the array offset
     * @param positionCount the position count
     * @param valueIsNull the value is null
     * @param values the values
     */
    ByteArrayOmniBlock(int arrayOffset, int positionCount, byte[] valueIsNull, BooleanVec values)
    {
        this.vecAllocator = values.getAllocator();
        if (arrayOffset < 0) {
            throw new IllegalArgumentException("arrayOffset is negative");
        }
        this.arrayOffset = arrayOffset;

        if (positionCount < 0) {
            throw new IllegalArgumentException("positionCount is negative");
        }
        this.positionCount = positionCount;

        if (values.getSize() < positionCount) {
            throw new IllegalArgumentException("values length is less than positionCount");
        }
        this.values = values;

        if (valueIsNull != null && valueIsNull.length - arrayOffset < positionCount) {
            throw new IllegalArgumentException("isNull length is less than positionCount");
        }
        this.valueIsNull = valueIsNull;

        sizeInBytes = (Byte.BYTES + Byte.BYTES) * (long) positionCount;
        retainedSizeInBytes = INSTANCE_SIZE + sizeOf(valueIsNull) + this.values.getCapacityInBytes();
    }

    @Override
    public Object getValues()
    {
        return values;
    }

    @Override
    public void setClosable(boolean isClosable)
    {
        values.setClosable(isClosable);
    }

    @Override
    public long getSizeInBytes()
    {
        return sizeInBytes;
    }

    @Override
    public long getRegionSizeInBytes(int position, int length)
    {
        return (Byte.BYTES + Byte.BYTES) * (long) length;
    }

    @Override
    public long getPositionsSizeInBytes(boolean[] positions)
    {
        return (Byte.BYTES + Byte.BYTES) * (long) countUsedPositions(positions);
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return retainedSizeInBytes;
    }

    @Override
    public long getEstimatedDataSizeForStats(int position)
    {
        return isNull(position) ? 0 : Byte.BYTES;
    }

    @Override
    public void retainedBytesForEachPart(BiConsumer<Object, Long> consumer)
    {
        consumer.accept(values.get(0, positionCount), (long) values.getCapacityInBytes());
        if (valueIsNull != null) {
            consumer.accept(valueIsNull, sizeOf(valueIsNull));
        }
        consumer.accept(this, (long) INSTANCE_SIZE);
    }

    @Override
    public int getPositionCount()
    {
        return positionCount;
    }

    @Override
    public byte getByte(int position, int offset)
    {
        checkReadablePosition(position);
        if (offset != 0) {
            throw new IllegalArgumentException("offset must be zero");
        }
        return values.get(position) ? (byte) 1 : (byte) 0;
    }

    @Override
    public long getLong(int position, int offset)
    {
        return getByte(position, offset);
    }

    @Override
    public void close()
    {
        values.close();
    }

    @Override
    public boolean isExtensionBlock()
    {
        return true;
    }

    @Override
    public boolean mayHaveNull()
    {
        return valueIsNull != null;
    }

    @Override
    public boolean isNull(int position)
    {
        checkReadablePosition(position);
        return valueIsNull != null && valueIsNull[position + arrayOffset] == Vec.NULL;
    }

    @Override
    public void writePositionTo(int position, BlockBuilder blockBuilder)
    {
        checkReadablePosition(position);
        blockBuilder.writeByte(values.get(position) ? (byte) 1 : (byte) 0);
        blockBuilder.closeEntry();
    }

    @Override
    public Block getSingleValueBlock(int position)
    {
        checkReadablePosition(position);
        return new ByteArrayOmniBlock(vecAllocator, 0, 1, isNull(position) ? new byte[]{Vec.NULL} : null,
                new byte[]{(values.get(position) ? (byte) 1 : (byte) 0)});
    }

    @Override
    public Block copyPositions(int[] positions, int offset, int length)
    {
        checkArrayRange(positions, offset, length);
        byte[] newValueIsNull = null;
        BooleanVec newValues = values.copyPositions(positions, offset, length);
        if (valueIsNull != null) {
            newValueIsNull = newValues.getRawValueNulls();
        }
        return new ByteArrayOmniBlock(0, length, newValueIsNull, newValues);
    }

    @Override
    public Block getRegion(int positionOffset, int length)
    {
        checkValidRegion(getPositionCount(), positionOffset, length);
        BooleanVec newValues = values.slice(positionOffset, positionOffset + length);
        return new ByteArrayOmniBlock(newValues.getOffset(), length, valueIsNull, newValues);
    }

    @Override
    public Block copyRegion(int positionOffset, int length)
    {
        checkValidRegion(getPositionCount(), positionOffset, length);

        BooleanVec newValues = compactVec(values, positionOffset, length);
        byte[] newValueIsNull = valueIsNull == null
                ? null
                : compactArray(valueIsNull, positionOffset + arrayOffset, length);

        if (newValueIsNull == valueIsNull && newValues == values) {
            return this;
        }
        return new ByteArrayOmniBlock(0, length, newValueIsNull, newValues);
    }

    @Override
    public String getEncodingName()
    {
        return ByteArrayBlockEncoding.NAME;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("ByteArrayOmniBlock{");
        sb.append("positionCount=").append(getPositionCount());
        sb.append('}');
        return sb.toString();
    }

    private void checkReadablePosition(int position)
    {
        if (position < 0 || position >= getPositionCount()) {
            throw new IllegalArgumentException("position is not valid");
        }
    }

    @Override
    public boolean[] filter(BloomFilter filter, boolean[] validPositions)
    {
        for (int i = 0; i < positionCount; i++) {
            validPositions[i] = validPositions[i] && filter.test(values.get(i) ? (byte) 1 : (byte) 0);
        }
        return validPositions;
    }

    @Override
    public int filter(int[] positions, int positionCount, int[] matchedPositions, Function<Object, Boolean> test)
    {
        int matchCount = 0;
        for (int i = 0; i < positionCount; i++) {
            if (valueIsNull != null && valueIsNull[positions[i] + arrayOffset] == Vec.NULL) {
                if (test.apply(null)) {
                    matchedPositions[matchCount++] = positions[i];
                }
            }
            else if (test.apply(values.get(positions[i]))) {
                matchedPositions[matchCount++] = positions[i];
            }
        }
        return matchCount;
    }

    @Override
    public Byte get(int position)
    {
        if (valueIsNull != null && valueIsNull[position + arrayOffset] == Vec.NULL) {
            return null;
        }
        return values.get(position) ? (byte) 1 : (byte) 0;
    }
}

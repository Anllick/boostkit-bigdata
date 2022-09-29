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

package nova.hetu.olk.operator;

import io.prestosql.operator.Operator;
import io.prestosql.operator.OperatorFactory;
import io.prestosql.spi.Page;
import io.prestosql.spi.plan.AggregationNode.Step;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.type.Type;
import nova.hetu.olk.operator.AggregationOmniOperator.AggregationOmniOperatorFactory;
import nova.hetu.olk.tool.OperatorUtils;
import nova.hetu.omniruntime.constants.FunctionType;
import nova.hetu.omniruntime.operator.OmniOperator;
import nova.hetu.omniruntime.operator.aggregator.OmniAggregationOperatorFactory;
import nova.hetu.omniruntime.type.DataType;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static nova.hetu.olk.mock.MockUtil.mockNewVecWithAnyArguments;
import static nova.hetu.olk.mock.MockUtil.mockOmniOperator;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.powermock.api.mockito.PowerMockito.doReturn;

@PrepareForTest({
        AggregationOmniOperator.class,
        OperatorUtils.class
})
public class AggregationOmniOperatorTest
        extends AbstractOperatorTest
{
    private final int operatorId = new Random().nextInt();
    private final PlanNodeId planNodeId = new PlanNodeId(UUID.randomUUID().toString());
    private final List<Type> sourceTypes = Collections.emptyList();
    private final FunctionType[] aggregatorTypes = {};
    private final int[] aggregationInputChannels = {};
    private final List<Optional<Integer>> maskChannelList = Arrays.asList(Optional.of(1), Optional.of(2), Optional.empty());
    private final DataType[] aggregationOutputTypes = {};
    private final Step step = Step.SINGLE;
    private OmniOperator omniOperator;

    @Override
    protected void setUpMock()
    {
        super.setUpMock();
        omniOperator = mockOmniOperator();
    }

    @Override
    protected OperatorFactory createOperatorFactory()
    {
        OmniAggregationOperatorFactory factory = mockNewVecWithAnyArguments(OmniAggregationOperatorFactory.class);
        doReturn(omniOperator).when(factory).createOperator(any());
        return new AggregationOmniOperatorFactory(operatorId, planNodeId,
                sourceTypes, aggregatorTypes, aggregationInputChannels, maskChannelList, aggregationOutputTypes, step);
    }

    @Override
    protected void checkOperatorFactory(OperatorFactory operatorFactory)
    {
        super.checkOperatorFactory(operatorFactory);
        assertEquals(operatorFactory.getSourceTypes(), sourceTypes);
    }

    @Override
    protected Operator createOperator(Operator originalOperator)
    {
        return new AggregationOmniOperator(originalOperator.getOperatorContext(), omniOperator);
    }

    @Test(dataProvider = "pageProvider")
    public void testProcess(int i)
    {
        Operator operator = getOperator();

        Page page = getPageForTest(i);
        if (page == null) {
            assertThrows("page is null", NullPointerException.class, () -> operator.addInput(null));
        }
        else {
            operator.addInput(page);
        }

        assertFalse(operator.isFinished());
        operator.finish();
        operator.getOutput();
        if (page != null) {
            assertTrue(operator.isFinished());
        }
        assertNull(operator.getOutput());
    }
}

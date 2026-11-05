package io.github.bitlhk.finance.harness.calculation;

import com.fasterxml.jackson.databind.node.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculationContractTest {
    @Test void originalPrecisionAndNegativeZeroAreValidated() throws Exception{
        var raw=HarnessJson.mapper().readTree("{\"kind\":\"float\",\"value\":\"-0.0\",\"hex\":\"-0x0.0p+0\"}");
        CalculationResultMapper.validateRaw(raw,0);
        ((ObjectNode)raw).put("hex","0x0.0p+0");
        assertThrows(IllegalArgumentException.class,()->CalculationResultMapper.validateRaw(raw,0));
    }
    @Test void missingIsExplicitAndArbitraryTypesAreRejected() throws Exception{
        CalculationResultMapper.validateRaw(HarnessJson.mapper().readTree("{\"kind\":\"missing\",\"marker\":\"nan\"}"),0);
        var arbitrary=HarnessJson.mapper().readTree("{\"kind\":\"pickle\",\"value\":\"abc\"}");
        assertThrows(IllegalArgumentException.class,()->CalculationResultMapper.validateRaw(arbitrary,0));
    }
    @Test void tableShapeMustMatchOriginalIndexes() throws Exception{
        var node=HarnessJson.mapper().readTree("{\"kind\":\"dataframe\",\"columns\":[],\"index\":[],\"dtypes\":[],\"data\":[[]]}");
        assertThrows(IllegalArgumentException.class,()->CalculationResultMapper.validateRaw(node,0));
    }
    @Test void digestSortsObjectsButPreservesOrderedArrays() throws Exception{
        var a=HarnessJson.mapper().readTree("{\"b\":\"中文\",\"a\":[\"x\",\"y\"]}");
        var b=HarnessJson.mapper().readTree("{\"a\":[\"x\",\"y\"],\"b\":\"中文\"}");
        assertEquals(CalculationResultMapper.digest(a),CalculationResultMapper.digest(b));
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",CalculationResultMapper.digest(HarnessJson.mapper().readTree("{}")));
    }
}

package org.qubership.cloud.dbaas.rsql;

import com.fasterxml.jackson.core.type.TypeReference;
import org.qubership.cloud.dbaas.rsql.model.QueryPreparationPart;
import org.qubership.cloud.dbaas.rsql.model.QueryPreparationPartOverrideConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class QueryPreparationUtilsTest {

    @Test
    void testConvertToTypeWithSuccessfulConvertedType() {
        var expectedConvertedValue = 6L;
        var actualConvertedValue = QueryPreparationUtils.convertToType("6", new TypeReference<Long>() {});

        Assertions.assertEquals(expectedConvertedValue, actualConvertedValue);
    }

    @Test
    void testConvertToTypeWithFailedConvertedType() {
        Assertions.assertThrowsExactly(RuntimeException.class,
            () -> QueryPreparationUtils.convertToType("{6}", new TypeReference<Long>() {})
        );
    }

    @Test
    void testObtainParamNameThrowsNullPointerExceptionWhenQueryPreparationPartIsNull() {
        Assertions.assertThrowsExactly(
            NullPointerException.class,
            () -> QueryPreparationUtils.obtainParamName(null, null)
        );
    }

    @Test
    void testObtainParamNameReturnsOverriddenParamName() {
        var queryPreparationPart = new QueryPreparationPart(
            "originalParamName", null, null, null, null, null
        );

        var expectedParamName = "overriddenParamName";

        var overrideConfig = QueryPreparationPartOverrideConfig.builder()
            .paramName(expectedParamName)
            .build();

        var actualParamName = QueryPreparationUtils.obtainParamName(queryPreparationPart, overrideConfig);

        Assertions.assertEquals(expectedParamName, actualParamName);
    }

    @Test
    void testObtainParamNameReturnsOriginalParamNameWhenOverrideConfigHasNoOverriddenOne() {
        var expectedParamName = "originalParamName";

        var queryPreparationPart = new QueryPreparationPart(
            expectedParamName, null, null, null, null, null
        );

        var overrideConfig = QueryPreparationPartOverrideConfig.builder()
            .build();

        var actualParamName = QueryPreparationUtils.obtainParamName(queryPreparationPart, overrideConfig);

        Assertions.assertEquals(expectedParamName, actualParamName);
    }

    @Test
    void testObtainParamNameReturnsOriginalParamNameWhenOverrideConfigIsNull() {
        var expectedParamName = "originalParamName";

        var queryPreparationPart = new QueryPreparationPart(
            expectedParamName, null, null, null, null, null
        );

        var actualParamName = QueryPreparationUtils.obtainParamName(queryPreparationPart, null);

        Assertions.assertEquals(expectedParamName, actualParamName);
    }

    @Test
    void testObtainParamOperatorThrowsNullPointerExceptionWhenQueryPreparationPartAndOverrideConfigAreNulls() {
        Assertions.assertThrowsExactly(
            NullPointerException.class,
            () -> QueryPreparationUtils.obtainParamOperator(null, null)
        );
    }

    @Test
    void testObtainParamOperatorThrowsNullPointerExceptionWhenQueryPreparationPartIsNullAndOverrideConfigAreSpecified() {
        var overrideConfig = QueryPreparationPartOverrideConfig.builder()
            .rsqlAndQueryOperators(Map.of())
            .build();

        Assertions.assertThrowsExactly(
            NullPointerException.class,
            () -> QueryPreparationUtils.obtainParamOperator(null, overrideConfig)
        );
    }

    @Test
    void testObtainParamOperatorReturnsOverriddenParamOperator() {
        var queryPreparationPart = new QueryPreparationPart(
            null, "!=", null, "<>", null, null
        );

        var expectedParamOperator = "IS DISTINCT FROM";

        var overrideConfig = QueryPreparationPartOverrideConfig.builder()
            .rsqlAndQueryOperators(Map.of("!=", expectedParamOperator))
            .build();

        var actualParamOperator = QueryPreparationUtils.obtainParamOperator(queryPreparationPart, overrideConfig);

        Assertions.assertEquals(expectedParamOperator, actualParamOperator);
    }

    @Test
    void testObtainParamOperatorReturnsOriginalParamOperatorWhenOverrideConfigHasNoOverriddenOne() {
        var expectedParamOperator = "<>";

        var queryPreparationPart = new QueryPreparationPart(
            null, "!=", null, expectedParamOperator, null, null
        );

        var overrideConfig = QueryPreparationPartOverrideConfig.builder()
            .build();

        var actualParamOperator = QueryPreparationUtils.obtainParamOperator(queryPreparationPart, overrideConfig);

        Assertions.assertEquals(expectedParamOperator, actualParamOperator);
    }

    @Test
    void testObtainParamOperatorReturnsOriginalParamOperatorWhenOverrideConfigIsNull() {
        var expectedParamOperator = "<>";

        var queryPreparationPart = new QueryPreparationPart(
            null, "!=", null, expectedParamOperator, null, null
        );

        var actualParamOperator = QueryPreparationUtils.obtainParamOperator(queryPreparationPart, null);

        Assertions.assertEquals(expectedParamOperator, actualParamOperator);
    }

    @Test
    void testObtainParamValueThrowsNullPointerExceptionWhenQueryPreparationPartIsNull() {
        Assertions.assertThrowsExactly(
            NullPointerException.class,
            () -> QueryPreparationUtils.obtainParamValue(null, null)
        );
    }

    @Test
    void testObtainParamValueReturnsOverriddenParamValue() {
        var queryPreparationPart = new QueryPreparationPart(
            null, null, List.of("originalParamValue"), null, null, null
        );

        var expectedParamValue = "overriddenParamValue";

        var overrideConfig = QueryPreparationPartOverrideConfig.builder()
            .paramValue(expectedParamValue)
            .build();

        var actualParamValue = QueryPreparationUtils.obtainParamValue(queryPreparationPart, overrideConfig);

        Assertions.assertEquals(expectedParamValue, actualParamValue);
    }

    @Test
    void testObtainParamValueReturnsOriginalParamValueWhenOverrideConfigHasNoOverriddenOne() {
        var expectedParamValue = "originalParamValue";

        var queryPreparationPart = new QueryPreparationPart(
            null, null, List.of("originalParamValue"), null, null, null
        );

        var overrideConfig = QueryPreparationPartOverrideConfig.builder()
            .build();

        var actualParamValue = QueryPreparationUtils.obtainParamValue(queryPreparationPart, overrideConfig);

        Assertions.assertEquals(expectedParamValue, actualParamValue);
    }

    @Test
    void testObtainParamValueReturnsOriginalParamValueWhenOverrideConfigIsNull() {
        var expectedParamValue = "originalParamValue";

        var queryPreparationPart = new QueryPreparationPart(
            null, null, List.of("originalParamValue"), null, null, null
        );

        var actualParamValue = QueryPreparationUtils.obtainParamValue(queryPreparationPart, null);

        Assertions.assertEquals(expectedParamValue, actualParamValue);
    }

    @Test
    void testObtainQueryTemplateThrowsNullPointerExceptionWhenQueryPreparationPartIsNull() {
        Assertions.assertThrowsExactly(
            NullPointerException.class,
            () -> QueryPreparationUtils.obtainQueryTemplate(null, null)
        );
    }

    @Test
    void testObtainQueryTemplateReturnsOverriddenQueryTemplate() {
        var queryPreparationPart = new QueryPreparationPart(
            null, null, null, null, "originalQueryTemplate", null
        );

        var expectedQueryTemplate = "overriddenQueryTemplate";

        var overrideConfig = QueryPreparationPartOverrideConfig.builder()
            .queryTemplate(expectedQueryTemplate)
            .build();

        var actualQueryTemplate = QueryPreparationUtils.obtainQueryTemplate(queryPreparationPart, overrideConfig);

        Assertions.assertEquals(expectedQueryTemplate, actualQueryTemplate);
    }

    @Test
    void testObtainQueryTemplateReturnsOriginalQueryTemplateWhenOverrideConfigHasNoOverriddenOne() {
        var expectedQueryTemplate = "originalQueryTemplate";

        var queryPreparationPart = new QueryPreparationPart(
            null, null, null, null, expectedQueryTemplate, null
        );

        var overrideConfig = QueryPreparationPartOverrideConfig.builder()
            .build();

        var actualQueryTemplate = QueryPreparationUtils.obtainQueryTemplate(queryPreparationPart, overrideConfig);

        Assertions.assertEquals(expectedQueryTemplate, actualQueryTemplate);
    }

    @Test
    void testObtainQueryTemplateReturnsOriginalQueryTemplateWhenOverrideConfigIsNull() {
        var expectedQueryTemplate = "originalQueryTemplate";

        var queryPreparationPart = new QueryPreparationPart(
            null, null, null, null, expectedQueryTemplate, null
        );

        var actualQueryTemplate = QueryPreparationUtils.obtainQueryTemplate(queryPreparationPart, null);

        Assertions.assertEquals(expectedQueryTemplate, actualQueryTemplate);
    }

    @Test
    void testObtainParamValueMappingTypeReturnsSpecifiedParamValueMappingType() {
        var expectedParamValueMappingType = new TypeReference<Long>() {};

        var overrideConfig = QueryPreparationPartOverrideConfig.builder()
            .paramValueMappingType(expectedParamValueMappingType)
            .build();

        var actualParamValueMappingType = QueryPreparationUtils.obtainParamValueMappingType(overrideConfig);

        Assertions.assertEquals(expectedParamValueMappingType, actualParamValueMappingType);
    }

    @Test
    void testObtainParamValueMappingTypeReturnsNullParamValueMappingTypeWhenOverrideConfigHasNoSpecifiedOne() {
        var overrideConfig = QueryPreparationPartOverrideConfig.builder()
            .build();

        var actualParamValueMappingType = QueryPreparationUtils.obtainParamValueMappingType(overrideConfig);

        Assertions.assertNull(actualParamValueMappingType);
    }

    @Test
    void testObtainParamValueMappingTypeReturnsNullParamValueMappingTypeWhenOverrideConfigIsNull() {
        var actualParamValueMappingType = QueryPreparationUtils.obtainParamValueMappingType(null);

        Assertions.assertNull(actualParamValueMappingType);
    }
}

package com.hify.common.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.constant.ResultConstant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageResult} 单元测试。
 * <p>继承 + 泛型 + Jackson 三者叠加时，分页元信息是否真的被拍平到顶层、
 * 父类的 {@code data} 是否被序列化成数组，光看代码看不出来，必须用序列化结果钉住。
 */
class PageResultTest {

    /** JSON 序列化器，与 Spring MVC 默认使用的是同一个实现 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("of() 填齐父类三段与分页三段")
    void ofFillsBothLevels() {
        PageResult<String> result = PageResult.of(List.of("a", "b"), 137L, 1, 20);

        assertThat(result.getCode()).isEqualTo(ResultConstant.SUCCESS_CODE);
        assertThat(result.getMessage()).isEqualTo(ResultConstant.SUCCESS_MESSAGE);
        assertThat(result.getData()).containsExactly("a", "b");
        assertThat(result.getTotal()).isEqualTo(137L);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("list 传 null 时按 8.4 转成空列表,而不是 null")
    void nullListBecomesEmptyList() {
        PageResult<String> result = PageResult.of(null, 0L, 1, 20);

        assertThat(result.getData()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("序列化后 total/page/size 与 code/message/data 同级,data 是数组")
    void serializeFlattensPagingFields() throws Exception {
        String raw = objectMapper.writeValueAsString(PageResult.of(List.of("a", "b"), 137L, 1, 20));
        JsonNode json = objectMapper.readTree(raw);

        List<String> fieldNames = json.properties().stream().map(Map.Entry::getKey).toList();
        assertThat(fieldNames).containsExactlyInAnyOrder("code", "message", "data", "total", "page", "size");
        assertThat(json.get("data").isArray()).isTrue();
        assertThat(json.get("data")).hasSize(2);
        assertThat(json.get("total").asLong()).isEqualTo(137L);
        assertThat(json.get("page").asInt()).isEqualTo(1);
        assertThat(json.get("size").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("空列表序列化成 [] 而不是 null")
    void serializeEmptyListAsArray() throws Exception {
        String raw = objectMapper.writeValueAsString(PageResult.of(null, 0L, 1, 20));
        JsonNode json = objectMapper.readTree(raw);

        assertThat(json.get("data").isArray()).isTrue();
        assertThat(json.get("data")).isEmpty();
    }
}

package org.example.back.agent.trace;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolTraceRecorderTest {

    @Test
    void errorPayloadShouldMarkCallFailed() {
        AgentToolTraceRecorder recorder = new AgentToolTraceRecorder(6);
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = toolDefinition();
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(anyString(), isNull())).thenReturn(
                "{\"error\":\"FORBIDDEN\",\"message\":\"当前角色无权查询库存不足商品\"}");

        recorder.wrap(new ToolCallback[]{delegate}).get(0).call("{}", null);

        AgentToolTraceRecorder.RecordedCall call = recorder.getCalls().get(0);
        assertFalse(call.getSuccess());
        assertEquals("当前角色无权查询库存不足商品", call.getErrorMessage());
        assertEquals(0, call.getRowCount());
    }

    @Test
    void largeResultShouldBePersistedWithoutOldSixThousandTruncation() {
        AgentToolTraceRecorder recorder = new AgentToolTraceRecorder(6);
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = toolDefinition();
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(anyString(), isNull())).thenReturn(largeResult(50));

        recorder.wrap(new ToolCallback[]{delegate}).get(0).call("{}", null);

        AgentToolTraceRecorder.RecordedCall call = recorder.getCalls().get(0);
        assertTrue(call.getSuccess());
        assertEquals(50, call.getRowCount());
        assertTrue(call.getResultSummary().length() > 6000);
        assertTrue(call.getResultSummary().contains("\"goodsId\":50"));
    }

    @Test
    void exceptionShouldMarkCallFailed() {
        AgentToolTraceRecorder recorder = new AgentToolTraceRecorder(6);
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = toolDefinition();
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(anyString(), isNull())).thenThrow(new IllegalStateException("db down"));

        try {
            recorder.wrap(new ToolCallback[]{delegate}).get(0).call("{}", null);
        } catch (IllegalStateException ignored) {
            // expected
        }

        AgentToolTraceRecorder.RecordedCall call = recorder.getCalls().get(0);
        assertFalse(call.getSuccess());
        assertEquals("db down", call.getErrorMessage());
    }

    private ToolDefinition toolDefinition() {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("queryLowStockGoods");
        return definition;
    }

    private String largeResult(int rows) {
        List<String> items = new java.util.ArrayList<>();
        for (int i = 1; i <= rows; i++) {
            items.add("{\"goodsId\":" + i + ",\"goodsCode\":\"AGT-" + i
                    + "\",\"goodsName\":\"Agent演示商品名称用于测试截断边界" + i
                    + "\",\"stock\":2,\"warningStock\":10,\"gap\":-8,\"unit\":\"个\",\"status\":1}");
        }
        return "{\"tool\":\"queryLowStockGoods\",\"total\":" + rows
                + ",\"rows\":[" + String.join(",", items) + "]}";
    }
}

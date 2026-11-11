package org.example.back.agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 请求级工具调用轨迹记录器。
 *
 * <p>通过包装 ToolCallback 记录模型实际调用的工具、入参、结果摘要和耗时，
 * 不依赖工具方法内部埋点，也不使用线程变量。</p>
 */
public class AgentToolTraceRecorder {

    private static final int MAX_ARGUMENT_LENGTH = 6000;
    private static final int MAX_RESULT_LENGTH = 50000;
    private static final int MAX_ERROR_LENGTH = 500;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<RecordedCall> calls = new ArrayList<>();
    private final int maxCalls;

    public AgentToolTraceRecorder(int maxCalls) {
        this.maxCalls = maxCalls;
    }

    public List<ToolCallback> wrap(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> (ToolCallback) new TracingToolCallback(callback, this))
                .toList();
    }

    public List<RecordedCall> getCalls() {
        return List.copyOf(calls);
    }

    private RecordedCall begin(String toolName, String arguments) {
        if (calls.size() >= maxCalls) {
            throw new IllegalStateException("工具调用次数超过上限 " + maxCalls + "，已停止本次请求");
        }
        RecordedCall call = new RecordedCall();
        call.setToolName(toolName);
        call.setArguments(truncate(arguments, MAX_ARGUMENT_LENGTH));
        call.setSuccess(false);
        calls.add(call);
        return call;
    }

    private void finish(RecordedCall call, String result) {
        JsonNode root = parse(result);
        call.setResultSummary(truncate(result, MAX_RESULT_LENGTH));
        call.setRowCount(extractRowCount(root));
        if (root != null && root.has("error")) {
            call.setSuccess(false);
            String message = root.path("message").asText("工具返回错误");
            call.setErrorMessage(truncate(message, MAX_ERROR_LENGTH));
            return;
        }
        call.setSuccess(true);
    }

    private void fail(RecordedCall call, Throwable error) {
        call.setSuccess(false);
        call.setErrorMessage(error == null
                ? "未知工具异常"
                : truncate(error.getMessage(), MAX_ERROR_LENGTH));
    }

    private JsonNode parse(String result) {
        if (result == null || result.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(result);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer extractRowCount(JsonNode root) {
        if (root == null) {
            return 0;
        }
        if (root.has("rows") && root.get("rows").isArray()) {
            return root.get("rows").size();
        }
        if (root.has("total") && root.get("total").isNumber()) {
            return root.get("total").asInt();
        }
        return 0;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    @Data
    public static class RecordedCall {
        private String toolName;
        private String arguments;
        private String resultSummary;
        private Integer rowCount;
        private Boolean success;
        private Long latencyMs;
        private String errorMessage;
    }

    private static final class TracingToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final AgentToolTraceRecorder recorder;

        private TracingToolCallback(ToolCallback delegate, AgentToolTraceRecorder recorder) {
            this.delegate = delegate;
            this.recorder = recorder;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            RecordedCall call = recorder.begin(delegate.getToolDefinition().name(), toolInput);
            long started = System.currentTimeMillis();
            try {
                String result = delegate.call(toolInput, toolContext);
                call.setLatencyMs(System.currentTimeMillis() - started);
                recorder.finish(call, result);
                return result;
            } catch (RuntimeException e) {
                call.setLatencyMs(System.currentTimeMillis() - started);
                recorder.fail(call, e);
                throw e;
            }
        }
    }
}

package org.example.back.common.aspect;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.exception.BusinessException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.validation.BindingResult;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 防重复提交切面：在固定窗口内拦截同一用户的同类重复写请求。
 */
@Aspect
@Component
public class PreventDuplicateSubmitAspect {

    private static final int MAX_CACHE_SIZE = 10000;
    private static final String REDIS_KEY_PREFIX = "wms:dedup:";
    private static final com.fasterxml.jackson.databind.ObjectMapper FINGERPRINT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Map<String, Long> requestWindowMap = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Before("@annotation(preventDuplicateSubmit)")
    public void preventDuplicateSubmit(JoinPoint joinPoint, PreventDuplicateSubmit preventDuplicateSubmit) {
        long now = System.currentTimeMillis();
        long intervalMs = preventDuplicateSubmit.intervalMs();
        if (intervalMs <= 0) throw new IllegalArgumentException("防重窗口必须大于0");
        String requestKey = buildRequestKey(joinPoint);

        if (tryAcquireDistributedWindow(requestKey, intervalMs, preventDuplicateSubmit.message())) {
            return;
        }

        synchronized (requestWindowMap) {
            requestWindowMap.entrySet().removeIf(entry -> entry.getValue() <= now);
            if (requestWindowMap.containsKey(requestKey)) {
                throw BusinessException.validateFail(preventDuplicateSubmit.message());
            }
            if (requestWindowMap.size() >= MAX_CACHE_SIZE) {
                throw BusinessException.validateFail("服务繁忙，请稍后重试");
            }
            requestWindowMap.put(requestKey, now + intervalMs);
        }

    }

    /**
     * 优先使用 Redis 做分布式防重，确保多实例部署时仍能拦截重复请求。
     * Redis 不可用时返回 false，交由本地窗口兜底。
     */
    private boolean tryAcquireDistributedWindow(String requestKey, long intervalMs, String rejectMessage) {
        if (stringRedisTemplate == null) {
            return false;
        }
        try {
            String redisKey = REDIS_KEY_PREFIX + requestKey;
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "1", intervalMs, TimeUnit.MILLISECONDS);
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }
            throw BusinessException.validateFail(rejectMessage);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String buildRequestKey(JoinPoint joinPoint) {
        HttpServletRequest request = currentRequest();
        String path = request == null ? "unknown-path" : request.getRequestURI();
        String method = request == null ? "UNKNOWN" : request.getMethod();
        String actor = resolveActor(request);
        String argsFingerprint = fingerprintArgs(joinPoint.getArgs());

        return fingerprintArgs(new Object[]{actor, method, path, argsFingerprint});
    }

    private String resolveActor(HttpServletRequest request) {
        if (StpUtil.isLogin()) {
            return "uid:" + StpUtil.getLoginIdAsString();
        }
        if (request != null) {
            return "ip:" + request.getRemoteAddr();
        }
        return "anonymous";
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest();
        }
        return null;
    }

    private String fingerprintArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "no-args";
        }
        try {
            Object[] payload = Arrays.stream(args)
                    .filter(arg -> !(arg instanceof jakarta.servlet.ServletRequest))
                    .filter(arg -> !(arg instanceof jakarta.servlet.ServletResponse))
                    .filter(arg -> !(arg instanceof BindingResult))
                    .map(arg -> arg instanceof MultipartFile file ? new Object[]{"file", file.getName(), file.getOriginalFilename(), file.getContentType(), file.getSize()} : arg)
                    .toArray();
            String json = FINGERPRINT_MAPPER.writeValueAsString(payload);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("请求参数无法生成防重标识");
        }

    }

}

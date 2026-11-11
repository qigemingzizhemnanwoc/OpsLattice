package org.example.back.common.exception;

import org.example.back.common.result.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Preserve legacy API transport behavior outside the employee module. */
@ControllerAdvice
public class EmployeeHttpStatusAdvice implements ResponseBodyAdvice<Object> {
 public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) { return true; }
 public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type, Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
  String path=request.getURI().getPath();
  if ((path.startsWith("/api/system/employees") || path.startsWith("/system/employees")) && body instanceof Result<?> result) {
   Integer code=result.getCode();if(code!=null && code>=400 && code<=599)response.setStatusCode(HttpStatusCode.valueOf(code));
  }
  return body;
 }
}

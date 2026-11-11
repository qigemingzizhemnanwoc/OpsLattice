package org.example.back.service;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import javax.sql.DataSource;
import org.example.back.dto.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(classes=EmployeeAcceptanceTest.App.class)
@AutoConfigureMockMvc
class EmployeeAcceptanceTest {
 @SpringBootConfiguration
 @EnableAutoConfiguration(exclude={org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration.class})
 @Import({PurchaseStockFlowIntegrationTest.TestConfig.class,EmployeeService.class,org.example.back.controller.EmployeeController.class,org.example.back.config.SaTokenConfig.class,org.example.back.common.aspect.RequireAdminAspect.class,org.example.back.common.exception.GlobalExceptionHandler.class,org.example.back.common.exception.EmployeeHttpStatusAdvice.class})
 static class App { @Bean @Primary AuthService realAuth(){return new AuthService();} }
 @MockBean MessageService messages;
 @Autowired MockMvc mvc;
 @Autowired DataSource source;
 @Autowired EmployeeService service;
 @Autowired PurchaseStockFlowIntegrationTest.StockFlowInsertFault fault;
 String token(String role,String dept){
  RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(),new MockHttpServletResponse()));
  try{StpUtil.login(java.util.UUID.randomUUID().toString());var u=new LoginResponse.UserInfoVO();u.setId(900001L);u.setRole(role);u.setDeptCode(dept);StpUtil.getSession().set("userInfo",u);return StpUtil.getTokenValue();}finally{RequestContextHolder.resetRequestAttributes();}
 }
 @Test void actualTokenAndAuthorizationMatrix() throws Exception {
  mvc.perform(get("/system/employees/page")).andExpect(status().isUnauthorized());
  for(String[] row:new String[][]{{"employee","hr","403"},{"admin","sales","403"},{"admin","hr","200"},{"superadmin","","200"}}){String t=token(row[0],row[1]);mvc.perform(get("/system/employees/page").header("Authorization","Bearer "+t)).andExpect(status().is(Integer.parseInt(row[2])));}
  mvc.perform(put("/system/employees/1").header("Authorization","Bearer "+token("employee","hr")).contentType("application/json").content("{\"username\":\"tester\",\"empName\":\"test\",\"deptId\":1,\"phone\":\"13800000000\"}")).andExpect(status().isForbidden());
  mvc.perform(post("/system/employees").header("Authorization","Bearer "+token("admin","hr")).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
 }
 @Test void createAndUpdateRollBackAfterUserWrite() throws Exception {
  var db=new JdbcTemplate(source);String mark="TX"+java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16);
  Long dept=db.queryForObject("SELECT id FROM sys_dept WHERE dept_code='hr' AND is_deleted=0",Long.class);
  var dto=new EmployeeSaveDTO();dto.setUsername(mark);dto.setEmpName(mark);dto.setDeptId(dept);dto.setPhone("137"+String.format("%08d",new java.security.SecureRandom().nextInt(100000000)));
  String t=token("admin","hr");var req=new MockHttpServletRequest();req.addHeader("Authorization","Bearer "+t);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req,new MockHttpServletResponse()));
  try{
   fault.failEmployee=true;assertThrows(Exception.class,()->service.create(dto));fault.failEmployee=false;
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?",Integer.class,mark));
   service.create(dto);Long id=db.queryForObject("SELECT id FROM sys_employee WHERE emp_name=?",Long.class,mark);
   dto.setEmpName(mark+"changed");fault.failEmployee=true;assertThrows(Exception.class,()->service.update(id,dto));fault.failEmployee=false;
   assertEquals(mark,db.queryForObject("SELECT real_name FROM sys_user WHERE username=?",String.class,mark));
   assertEquals(mark,db.queryForObject("SELECT emp_name FROM sys_employee WHERE id=?",String.class,id));
   service.update(id,dto);assertEquals(mark+"changed",db.queryForObject("SELECT emp_name FROM sys_employee WHERE id=?",String.class,id));
  }finally{fault.failEmployee=false;RequestContextHolder.resetRequestAttributes();}
 }

 @Test void successfulHttpCreateUpdateAndDuplicatePhoneMessage() throws Exception {
  var db=new JdbcTemplate(source);String mark="HT"+java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16);
  Long dept=db.queryForObject("SELECT id FROM sys_dept WHERE dept_code='hr' AND is_deleted=0",Long.class);
  String phone="136"+String.format("%08d",new java.security.SecureRandom().nextInt(100000000));String t=token("admin","hr");
  String body="{\"username\":\"%s\",\"empName\":\"%s\",\"deptId\":%d,\"phone\":\"%s\"}".formatted(mark,mark,dept,phone);
  mvc.perform(post("/system/employees").header("Authorization","Bearer "+t).contentType("application/json").content(body)).andExpect(status().isOk());
  Long id=db.queryForObject("SELECT id FROM sys_employee WHERE emp_name=?",Long.class,mark);
  mvc.perform(put("/system/employees/"+id).header("Authorization","Bearer "+t).contentType("application/json").content(body)).andExpect(status().isOk());
  mvc.perform(post("/system/employees").header("Authorization","Bearer "+t).contentType("application/json").content(body.replace(mark,mark+"other"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.msg").value("手机号已存在"));
 }
}

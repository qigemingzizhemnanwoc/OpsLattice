package org.example.back.service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.example.back.mapper.SysEmployeeMapper;
import org.example.back.dto.EmployeeQueryDTO;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
@SpringJUnitConfig(PurchaseStockFlowIntegrationTest.TestConfig.class)
class EmployeeDirectoryIntegrationTest {
 @Autowired DataSource source;
 @Autowired SysEmployeeMapper mapper;
 @Test void mergedDirectoryFiltersSortsAndPaginatesWithoutWrites() {
  var db=new JdbcTemplate(source);String marker="EP"+java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16);
  Long dept=db.queryForObject("SELECT id FROM sys_dept WHERE is_deleted=0 LIMIT 1",Long.class);
  db.update("INSERT INTO sys_user(username,password,real_name,role,dept_id,status) VALUES(?,'test',?,'admin',?,1)",marker,marker,dept);
  db.update("INSERT INTO sys_employee(emp_code,emp_name,dept_id,status,create_time) VALUES(?,?,?,1,'2020-01-01')",marker,marker,dept);
  var q=new EmployeeQueryDTO();q.setEmpName(marker);assertEquals(2,mapper.directoryCount(q));
  var first=mapper.directoryPage(q,0,1);var second=mapper.directoryPage(q,1,1);
  assertEquals(1,first.size());assertEquals(1,second.size());assertTrue(first.get(0).getReadOnly());assertTrue(first.get(0).getId()<0);assertFalse(second.get(0).getReadOnly());
  q.setStatus(0);assertEquals(0,mapper.directoryCount(q));q.setStatus(1);q.setDeptId(dept);
  assertTrue(mapper.directoryPage(q,0,10).stream().allMatch(v->dept.equals(v.getDeptId())));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_employee WHERE emp_code=?",Integer.class,marker));
 }

 @Test void systemManagementDeptFilterReturnsReadOnlyAdmins() {
  var db=new JdbcTemplate(source);String marker="SM"+java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16);
  Long systemDept=db.queryForObject("SELECT id FROM sys_dept WHERE dept_code='system_management' AND is_deleted=0",Long.class);
  db.update("INSERT INTO sys_user(username,password,real_name,role,dept_id,status) VALUES(?,'test',?,'admin',?,1)",marker,marker,systemDept);
  var q=new EmployeeQueryDTO();q.setEmpName(marker);q.setDeptId(systemDept);
  assertEquals(1,mapper.directoryCount(q));
  var rows=mapper.directoryPage(q,0,10);
  assertEquals(1,rows.size());
  Long adminId=db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,marker);
  assertEquals(-adminId.longValue(),rows.get(0).getId().longValue());
  assertTrue(rows.get(0).getReadOnly());
  assertEquals(systemDept,rows.get(0).getDeptId());
 }

 @Test void concurrentPhoneIsUniqueAndDeletedPhoneCanBeReused() throws Exception {
  var db=new JdbcTemplate(source);String marker="UQ"+java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16);
  String phone="139"+String.format("%08d",new java.security.SecureRandom().nextInt(100000000));
  var gate=new java.util.concurrent.CyclicBarrier(2);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  java.util.concurrent.Callable<Boolean> task=()->{gate.await(10,java.util.concurrent.TimeUnit.SECONDS);try{db.update("INSERT INTO sys_user(username,password,real_name,role,phone) VALUES(?,'test',?,'employee',?)",marker+Thread.currentThread().getId(),marker,phone);return true;}catch(org.springframework.dao.DuplicateKeyException e){var result=new org.example.back.common.exception.GlobalExceptionHandler().handleDuplicateKey(e);assertEquals(409,result.getCode());assertEquals("手机号已被使用",result.getMsg());return false;}};
  try{var a=pool.submit(task);var b=pool.submit(task);assertEquals(1,(a.get(20,java.util.concurrent.TimeUnit.SECONDS)?1:0)+(b.get(20,java.util.concurrent.TimeUnit.SECONDS)?1:0));}finally{pool.shutdownNow();}
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE phone=? AND is_deleted=0",Integer.class,phone));
  db.update("UPDATE sys_user SET is_deleted=1 WHERE real_name=?",marker);
  db.update("INSERT INTO sys_user(username,password,real_name,role,phone) VALUES(?,'test',?,'employee',?)",marker+"new",marker,phone);
  assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE real_name=?",Integer.class,marker));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE phone=? AND is_deleted=0",Integer.class,phone));
 }

 @Test void slimCountMatchesDirectoryWithDeletedRowsAndCombinedFilters() {
  var db=new JdbcTemplate(source);String marker="CT"+java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16);
  Long dept=db.queryForObject("SELECT id FROM sys_dept WHERE is_deleted=0 LIMIT 1",Long.class);
  for(int status=0;status<=1;status++) {
   db.update("INSERT INTO sys_employee(emp_code,emp_name,dept_id,status) VALUES(?,?,?,?)",marker+status,marker,dept,status);
   db.update("INSERT INTO sys_user(username,password,real_name,role,dept_id,status) VALUES(?,'test',?,'admin',?,?)",marker+status,marker,dept,status);
  }
  db.update("INSERT INTO sys_employee(emp_code,emp_name,dept_id,status,is_deleted) VALUES(?,?,?,1,1)",marker+"d",marker,dept);
  db.update("INSERT INTO sys_user(username,password,real_name,role,dept_id,status,is_deleted) VALUES(?,'test',?,'admin',?,1,1)",marker+"deleted",marker,dept);
  for(Integer status:new Integer[]{null,0,1})for(Long filterDept:new Long[]{null,dept,-1L}) {
   var q=new EmployeeQueryDTO();q.setEmpName(marker);q.setStatus(status);q.setDeptId(filterDept);
   assertEquals(mapper.directoryPage(q,0,100).size(),mapper.directoryCount(q),"Count must preserve UNION ALL filtering and logical deletion");
  }
  var q=new EmployeeQueryDTO();q.setEmpName(marker);assertEquals(4,mapper.directoryCount(q));
 }
}

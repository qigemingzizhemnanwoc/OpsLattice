package org.example.back.service;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.example.back.common.aspect.PreventDuplicateSubmitAspect;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.exception.BusinessException;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(classes=EmployeeAcceptanceTest.App.class)
class RedisDuplicateIntegrationTest {
 @org.springframework.boot.test.mock.mockito.MockBean MessageService messages;
 public static class Endpoint {
  final AtomicInteger calls=new AtomicInteger();
  @PreventDuplicateSubmit(intervalMs=300) public void submit(String value){calls.incrementAndGet();}
 }
 Endpoint proxy(Endpoint target, StringRedisTemplate redis){var aspect=new PreventDuplicateSubmitAspect();ReflectionTestUtils.setField(aspect,"stringRedisTemplate",redis);var f=new AspectJProxyFactory(target);f.addAspect(aspect);return f.getProxy();}
 void call(Endpoint endpoint,String value){var r=new MockHttpServletRequest("POST","/test/"+getClass().getSimpleName());r.setRemoteAddr("127.0.0.1");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));try{endpoint.submit(value);}finally{RequestContextHolder.resetRequestAttributes();}}
 @Test void realRedisAtomicWindowAndExpiry() throws Exception {
  var cf=new LettuceConnectionFactory("127.0.0.1",16379);cf.afterPropertiesSet();cf.start();
  try{var redis=new StringRedisTemplate(cf);assertEquals("PONG",redis.getConnectionFactory().getConnection().ping());var target=new Endpoint();var endpoint=proxy(target,redis);String key=java.util.UUID.randomUUID().toString();
   call(endpoint,key);assertThrows(BusinessException.class,()->call(endpoint,key));call(endpoint,key+"different");assertEquals(2,target.calls.get());Thread.sleep(400);call(endpoint,key);assertEquals(3,target.calls.get());
   var pool=Executors.newFixedThreadPool(8);var gate=new CyclicBarrier(8);String concurrent=key+"concurrent";
   try{var futures=new java.util.ArrayList<Future<?>>();for(int i=0;i<8;i++)futures.add(pool.submit(()->{gate.await();try{call(endpoint,concurrent);}catch(BusinessException expected){}return null;}));for(var future:futures)future.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertEquals(4,target.calls.get());
  }finally{cf.destroy();}
 }
 @Test void localFallbackWindowExpires() throws Exception {
  var target=new Endpoint();var endpoint=proxy(target,null);call(endpoint,"fallback");assertThrows(BusinessException.class,()->call(endpoint,"fallback"));Thread.sleep(400);call(endpoint,"fallback");assertEquals(2,target.calls.get());
 }

 @Test void realSessionsSeparateUsersAndPaths() {
  var cf=new LettuceConnectionFactory("127.0.0.1",16379);cf.afterPropertiesSet();cf.start();
  try{var target=new Endpoint();var endpoint=proxy(target,new StringRedisTemplate(cf));String value=java.util.UUID.randomUUID().toString();
   for(int i=0;i<2;i++){
    var request=new MockHttpServletRequest("POST","/same");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request,new org.springframework.mock.web.MockHttpServletResponse()));
    try{cn.dev33.satoken.stp.StpUtil.login(java.util.UUID.randomUUID().toString());endpoint.submit(value);assertThrows(BusinessException.class,()->endpoint.submit(value));request.setRequestURI("/different");endpoint.submit(value);cn.dev33.satoken.stp.StpUtil.logout();}finally{RequestContextHolder.resetRequestAttributes();}
   }assertEquals(4,target.calls.get());
  }finally{cf.destroy();}
 }
 @Test void fingerprintsPreserveNullArrayBoundariesAndFileMetadata() {
  var aspect=new PreventDuplicateSubmitAspect();
  String a=ReflectionTestUtils.invokeMethod(aspect,"fingerprintArgs",(Object)new Object[]{"a|b","c"});
  String b=ReflectionTestUtils.invokeMethod(aspect,"fingerprintArgs",(Object)new Object[]{"a","b|c"});assertNotEquals(a,b);
  assertNotEquals(a,ReflectionTestUtils.invokeMethod(aspect,"fingerprintArgs",(Object)new Object[]{null,"a|b","c"}));
  var file=new org.springframework.mock.web.MockMultipartFile("file","sample.txt","text/plain",new byte[]{1,2});
  String fingerprint=ReflectionTestUtils.invokeMethod(aspect,"fingerprintArgs",(Object)new Object[]{file,new int[]{1,2},java.util.Map.of("x",1)});assertEquals(64,fingerprint.length());assertFalse(fingerprint.contains("sample"));
 }
 @Test void fallbackCapacityAndMixedExpiry() throws Exception {
  var aspect=new PreventDuplicateSubmitAspect();
  @SuppressWarnings("unchecked") var map=(java.util.Map<String,Long>)ReflectionTestUtils.getField(aspect,"requestWindowMap");
  long now=System.currentTimeMillis();for(int i=0;i<10000;i++)map.put("key"+i,now+60000);map.put("expired",now-1);
  var target=new Endpoint();var factory=new AspectJProxyFactory(target);factory.addAspect(aspect);Endpoint endpoint=factory.getProxy();
  assertThrows(BusinessException.class,()->call(endpoint,"new"));assertEquals(10000,map.size());assertFalse(map.containsKey("expired"));assertEquals(0,target.calls.get());
  map.put("key0",now-1);call(endpoint,"new");assertEquals(10000,map.size());assertEquals(1,target.calls.get());
 }

 @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource database;
 @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
 public static class BusinessEndpoint extends Endpoint {
  org.springframework.jdbc.core.JdbcTemplate db;
  org.springframework.transaction.support.TransactionTemplate tx;
  boolean fail;
  @Override @PreventDuplicateSubmit(intervalMs=60000) public void submit(String value){
   calls.incrementAndGet();tx.executeWithoutResult(status->{
    db.update("INSERT INTO sys_user(username,password,real_name,role) VALUES(?,'test',?,'employee')",value,value);
    if(fail)throw new IllegalStateException("injected business failure");
   });
  }
 }
 Process startOwnedRedis(int port) throws Exception {
  var log=java.nio.file.Files.createTempFile("owned-redis-", ".log");
  var process=new ProcessBuilder(System.getenv().getOrDefault("REDIS_TEST_SERVER", "D:/rides/Redis-x64-5.0.14.1/redis-server.exe"),"--bind","127.0.0.1","--port",String.valueOf(port),"--save","","--appendonly","no").redirectErrorStream(true).redirectOutput(log.toFile()).start();
  for(int i=0;i<100;i++){if(!process.isAlive())throw new IllegalStateException("Owned Redis failed to start");try(var socket=new java.net.Socket("127.0.0.1",port)){return process;}catch(java.io.IOException e){Thread.sleep(50);}}
  process.destroy();throw new IllegalStateException("Owned Redis startup timeout");
 }
 @Test void ownedRedisDisconnectRecoveryAndDatabaseFailure() throws Exception {
  int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
  Process owned=startOwnedRedis(port);
  var config=new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1",port);
  var client=org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder().commandTimeout(java.time.Duration.ofMillis(150)).shutdownTimeout(java.time.Duration.ZERO).build();
  var cf=new LettuceConnectionFactory(config,client);cf.afterPropertiesSet();cf.start();
  var target=new BusinessEndpoint();target.db=new org.springframework.jdbc.core.JdbcTemplate(database);target.tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  var endpoint=proxy(target,new StringRedisTemplate(cf));String marker="RD"+java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16);
  try{
   call(endpoint,marker);assertThrows(BusinessException.class,()->call(endpoint,marker));assertEquals(1,target.calls.get());
   owned.destroy();assertTrue(owned.waitFor(10,TimeUnit.SECONDS));
   assertThrows(org.springframework.dao.DataAccessException.class,()->{try(var connection=cf.getConnection()){connection.ping();}});
   target.fail=true;String failed=marker+"fail";assertThrows(IllegalStateException.class,()->call(endpoint,failed));
   assertEquals(0,target.db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?",Integer.class,failed));
   assertThrows(BusinessException.class,()->call(endpoint,failed));target.fail=false;
   String local=marker+"local";call(endpoint,local);assertThrows(BusinessException.class,()->call(endpoint,local));
   owned=startOwnedRedis(port);cf.resetConnection();try(var connection=cf.getConnection()){assertEquals("PONG",connection.ping());}
   // Recovery has no copy of fallback keys: one extra attempt reaches MySQL,
   // where the persistent unique constraint still rejects the duplicate.
   assertThrows(org.springframework.dao.DuplicateKeyException.class,()->call(endpoint,local));
   assertEquals(1,target.db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?",Integer.class,local));
   assertThrows(BusinessException.class,()->call(endpoint,local));
  }finally{cf.destroy();if(owned.isAlive()){owned.destroy();owned.waitFor(10,TimeUnit.SECONDS);}}
 }
 @Test void concurrentFallbackNeverExceedsCapacity() throws Exception {
  var aspect=new PreventDuplicateSubmitAspect();
  @SuppressWarnings("unchecked") var map=(java.util.Map<String,Long>)ReflectionTestUtils.getField(aspect,"requestWindowMap");
  for(int i=0;i<9995;i++)map.put("occupied"+i,System.currentTimeMillis()+60000);
  var target=new Endpoint();var f=new AspectJProxyFactory(target);f.addAspect(aspect);Endpoint endpoint=f.getProxy();
  var pool=Executors.newFixedThreadPool(16);var gate=new CyclicBarrier(16);
  try{var futures=new java.util.ArrayList<Future<?>>();for(int i=0;i<16;i++){final int n=i;futures.add(pool.submit(()->{gate.await();try{call(endpoint,"capacity"+n);}catch(BusinessException expected){}return null;}));}for(var future:futures)future.get(15,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
  assertEquals(10000,map.size());assertEquals(5,target.calls.get());
 }
}




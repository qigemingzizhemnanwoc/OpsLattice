package org.example.back.service;
import org.junit.jupiter.api.Test;
import org.example.back.mapper.*;
import org.example.back.dto.EmployeeQueryDTO;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class EmployeeReadOnlyTest {
 @Test void pageOnlyUsesDirectoryQueries() {
  var service=new EmployeeService();var mapper=mock(SysEmployeeMapper.class);var auth=mock(AuthzService.class);
  ReflectionTestUtils.setField(service,"sysEmployeeMapper",mapper);ReflectionTestUtils.setField(service,"authzService",auth);
  var q=new EmployeeQueryDTO();when(mapper.directoryCount(q)).thenReturn(0L);when(mapper.directoryPage(q,0,10)).thenReturn(java.util.List.of());
  assertNotNull(service.page(q));verify(mapper).directoryCount(q);verify(mapper).directoryPage(q,0,10);verifyNoMoreInteractions(mapper);
 }
}

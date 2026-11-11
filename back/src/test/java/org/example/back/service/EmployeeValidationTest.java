package org.example.back.service;
import jakarta.validation.Validation;
import org.example.back.dto.EmployeeSaveDTO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EmployeeValidationTest {
 @Test void validatesEmployeeInputAndOptionalStatus() {
  try(var factory=Validation.buildDefaultValidatorFactory()) {
   var validator=factory.getValidator();var dto=new EmployeeSaveDTO();
   dto.setUsername("employee");dto.setEmpName("测试员工");dto.setDeptId(1L);dto.setPhone("13800000000");dto.setEmail("person@example.com");
   assertTrue(validator.validate(dto).isEmpty());
   dto.setPhone("123");assertTrue(validator.validate(dto).stream().anyMatch(v->v.getPropertyPath().toString().equals("phone")));
   dto.setPhone("13800000000");dto.setEmail("bad-email");dto.setUsername("x".repeat(51));dto.setStatus(2);
   var paths=validator.validate(dto).stream().map(v->v.getPropertyPath().toString()).toList();
   assertTrue(paths.containsAll(java.util.List.of("email","username","status")));
  }
 }
}

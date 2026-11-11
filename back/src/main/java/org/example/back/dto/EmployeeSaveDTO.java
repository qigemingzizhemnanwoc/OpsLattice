package org.example.back.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import jakarta.validation.constraints.*;

@Data
public class EmployeeSaveDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名最多50个字符")
    private String username;

    @NotBlank(message = "员工姓名不能为空")
    @Size(max = 50, message = "员工姓名最多50个字符")
    private String empName;

    @NotNull(message = "所属部门不能为空")
    @Positive(message = "部门ID必须为正数")
    private Long deptId;

    @Size(max = 50, message = "职位最多50个字符")
    private String position;
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "1[3-9][0-9]{9}", message = "请输入有效的11位手机号")
    private String phone;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最多100个字符")
    private String email;

    @Min(value = 0, message = "状态只能为0或1")
    @Max(value = 1, message = "状态只能为0或1")
    private Integer status;
}
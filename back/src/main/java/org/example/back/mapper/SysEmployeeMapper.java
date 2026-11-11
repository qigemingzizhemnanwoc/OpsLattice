package org.example.back.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.back.entity.SysEmployee;

public interface SysEmployeeMapper extends BaseMapper<SysEmployee> {
    java.util.List<org.example.back.vo.EmployeeVO> directoryPage(@org.apache.ibatis.annotations.Param("q") org.example.back.dto.EmployeeQueryDTO q, @org.apache.ibatis.annotations.Param("offset") long offset, @org.apache.ibatis.annotations.Param("size") long size);
    long directoryCount(@org.apache.ibatis.annotations.Param("q") org.example.back.dto.EmployeeQueryDTO q);

}
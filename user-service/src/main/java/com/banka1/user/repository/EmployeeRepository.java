package com.banka1.user.repository;

import com.banka1.common.model.Department;
import com.banka1.user.model.Employee;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeRepository extends UserRepository<Employee> {

    List<Employee> findByDepartment(Department department);

    boolean existsById(@NonNull Long id);
}

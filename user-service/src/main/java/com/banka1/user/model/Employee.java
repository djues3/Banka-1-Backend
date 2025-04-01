package com.banka1.user.model;

import com.banka1.common.model.Department;
import com.banka1.user.model.helper.Gender;
import com.banka1.common.model.Permission;
import com.banka1.common.model.Position;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Getter
@Setter
@DiscriminatorValue("employee")
public class Employee  extends User {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Position position; // pozicija u banci, direktor, menadzer, radnik...

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Department department; // odeljenje u banci, racunovodstvo, marketing, prodaja...

    @Column(nullable = false)
    private Boolean active;

    @Column(nullable = false)
    private Boolean isAdmin;

}

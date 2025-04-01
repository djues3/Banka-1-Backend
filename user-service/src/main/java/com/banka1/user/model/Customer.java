package com.banka1.user.model;

import jakarta.persistence.*;

@Entity
@DiscriminatorValue("customer")
public class Customer extends User {

}

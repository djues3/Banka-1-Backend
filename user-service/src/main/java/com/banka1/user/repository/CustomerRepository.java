package com.banka1.user.repository;

import com.banka1.user.model.Customer;

import org.springframework.stereotype.Repository;

@Repository
public interface CustomerRepository extends UserRepository<Customer> {}

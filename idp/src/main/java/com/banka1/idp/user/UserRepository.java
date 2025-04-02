package com.banka1.idp.user;

import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;
import java.util.UUID;

interface UserRepository extends ListCrudRepository<User, UUID> {

    Optional<User> findByEmail(String email);
}

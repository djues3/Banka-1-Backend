package com.banka1.user.repository;

import com.banka1.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.Optional;

@NoRepositoryBean
public interface UserRepository<T extends User> extends JpaRepository<T, Long> {

	Optional<T> findByEmail(String email);

	boolean existsByEmail(String email);
}

package com.banka1.user.model;

import com.banka1.user.model.helper.Gender;
import com.banka1.common.model.Permission;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "user_type")
@Table(name = "\"user\"")
@Getter
@Setter
public abstract class User {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String firstName;

	@Column(nullable = false)
	private String lastName;

	@Column(nullable = false)
	private String birthDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Gender gender;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(nullable = false)
	private String phoneNumber;

	@Column(nullable = false)
	private String address;

	@Column(nullable = false, unique = true)
	private String username;

	@Column
	private String password;

	@Column
	private String saltPassword;

	private String verificationCode;

	@ElementCollection(fetch = FetchType.EAGER)
	@Enumerated(EnumType.STRING)
	@CollectionTable(name = "user_permissions", joinColumns = @JoinColumn(name = "user_id"))
	@Column(name = "permission")
	private List<Permission> permissions;
}
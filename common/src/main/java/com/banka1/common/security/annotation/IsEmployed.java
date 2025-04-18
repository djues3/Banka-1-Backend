package com.banka1.common.security.annotation;


import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;

@Documented
@PreAuthorize("authentication.isEmployed or authentication.isAdmin")
public @interface IsEmployed {}

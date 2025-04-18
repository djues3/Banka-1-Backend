package com.banka1.common.annotation;

import com.banka1.common.security.SharedSecurityConfig;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Custom meta-annotation that combines several Spring-specific annotations to simplify the configuration
 * for a Spring Boot application. It is tailored for applications that require additional security
 * configurations, particularly for handling JWT authentication and resource security.
*/
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootApplication
@Import(SharedSecurityConfig.class)
public @interface Bank1Application {
}

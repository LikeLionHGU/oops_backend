package com.example.oops.client;

import java.lang.annotation.*;

/** Trusted classpath schema for an explicitly opted-in response DTO. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JsonSchemaOutput {
    String name();
    String resource();
}

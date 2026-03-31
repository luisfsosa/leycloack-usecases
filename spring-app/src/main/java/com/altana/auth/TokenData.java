package com.altana.auth;

import java.util.List;

/*
 * CONCEPT: Java Record (Java 16+)
 * Immutable class with constructor, getters, equals/hashCode/toString generated automatically.
 * Equivalent to Python's @dataclass.
 */
public record TokenData(
        String sub,
        String username,
        String email,
        List<String> roles,
        String tenantId,   // B2B2C: "toyota" | "ford" | null
        String userType    // B2B2C: "employee" | "customer" | null
) {}

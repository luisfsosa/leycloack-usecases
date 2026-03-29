package com.altana.auth;

import java.util.List;

/*
 * CONCEPTO: Java Record (Java 16+)
 * Clase inmutable con constructor, getters, equals/hashCode/toString automáticos.
 * Equivalente al @dataclass de Python.
 */
public record TokenData(
        String sub,
        String username,
        String email,
        List<String> roles,
        String tenantId,   // B2B2C: "toyota" | "ford" | null
        String userType    // B2B2C: "employee" | "customer" | null
) {}

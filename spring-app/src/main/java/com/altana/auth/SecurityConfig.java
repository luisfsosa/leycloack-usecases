package com.altana.auth;

/*
 * CONCEPTO: Spring Security 6+ — SecurityFilterChain
 *
 * No más WebSecurityConfigurerAdapter (eliminado en Spring Security 6).
 * La configuración se hace con un @Bean SecurityFilterChain.
 *
 * ENTREVISTA: ¿Diferencia entre hasRole() y hasAuthority()?
 *   hasRole("ANALYST")           → busca "ROLE_ANALYST" (añade prefijo)
 *   hasAuthority("ROLE_ANALYST") → busca "ROLE_ANALYST" literalmente
 *   Como nuestros roles ya tienen "ROLE_", usamos hasAuthority().
 */

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final KeycloakJwtConverter keycloakJwtConverter;

    public SecurityConfig(KeycloakJwtConverter keycloakJwtConverter) {
        this.keycloakJwtConverter = keycloakJwtConverter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/supply-chain/health").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/supply-chain/suppliers/**")
                    .hasAuthority("ROLE_ADMIN")
                .requestMatchers("/supply-chain/shipments")
                    .hasAnyAuthority("ROLE_ANALYST", "ROLE_ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtConverter))
            );

        return http.build();
    }

    /*
     * CONCEPTO: CORS (Cross-Origin Resource Sharing)
     * El browser bloquea requests de origen distinto (localhost:5173 → localhost:8081)
     * a menos que el servidor incluya los headers CORS correctos.
     *
     * ENTREVISTA: ¿Por qué configuras CORS en Spring y no en el proxy?
     * → En producción se configura en el API Gateway/proxy (nginx, Kong).
     *   En desarrollo local cada servicio lo maneja para simplicidad.
     *   NUNCA usar allowedOrigins("*") en producción con allowCredentials(true).
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
            "http://localhost:5173",  // Vite dev server
            "http://localhost:3000"   // alternativa
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

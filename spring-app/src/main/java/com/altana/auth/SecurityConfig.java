package com.altana.auth;

/*
 * CONCEPT: Spring Security 6+ — SecurityFilterChain
 *
 * No more WebSecurityConfigurerAdapter (removed in Spring Security 6).
 * Configuration is done with a @Bean SecurityFilterChain.
 *
 * INTERVIEW: Difference between hasRole() and hasAuthority()?
 *   hasRole("ANALYST")           → looks for "ROLE_ANALYST" (adds prefix)
 *   hasAuthority("ROLE_ANALYST") → looks for "ROLE_ANALYST" literally
 *   Since our roles already have "ROLE_", we use hasAuthority().
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
     * CONCEPT: CORS (Cross-Origin Resource Sharing)
     * The browser blocks cross-origin requests (localhost:5173 → localhost:8081)
     * unless the server includes the correct CORS headers.
     *
     * INTERVIEW: Why configure CORS in Spring and not in the proxy?
     * → In production it is configured in the API Gateway/proxy (nginx, Kong).
     *   In local development each service handles it for simplicity.
     *   NEVER use allowedOrigins("*") in production with allowCredentials(true).
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
            "http://localhost:5173",  // Vite dev server
            "http://localhost:3000"   // alternative
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

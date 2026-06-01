package com.thisjowi.auth.config;

import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.thisjowi.auth.filters.JwtAuthenticationFilter;
import com.thisjowi.auth.filters.RateLimitingFilter;
import com.thisjowi.auth.service.CustomOAuth2UserService;
import com.thisjowi.auth.handler.OAuth2LoginSuccessHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter,
            RateLimitingFilter rateLimitingFilter,
            CustomOAuth2UserService customOAuth2UserService,
            OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.rateLimitingFilter = rateLimitingFilter;
        this.customOAuth2UserService = customOAuth2UserService;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.disable())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(rateLimitingFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Public-specific routes
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/initiate-register",
                                "/api/v1/auth/resend-verification",
                                "/api/v1/auth/login",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/verify-reset-otp",
                                "/api/v1/auth/reset-password",
                                "/api/v1/auth/google",
                                "/api/v1/auth/github",
                                "/api/v1/auth/verify-email",
                                "/register",
                                "/login",
                                "/favicon.ico",
                                "/oauth2/**",
                                "/login/oauth2/code/**")
                        .permitAll()
                        // LDAP routes - public for login, organization lookup
                        .requestMatchers(
                                "/api/v1/auth/ldap/login",
                                "/api/v1/auth/ldap/test-connection",
                                "/api/v1/auth/organizations",
                                "/api/v1/auth/organizations/**")
                        .permitAll()
                        // Token and user validation routes
                        .requestMatchers(
                                "/api/v1/auth/user",
                                "/api/v1/auth/validate")
                        .permitAll()
                        // Documentation routes
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/actuator/**")
                        .permitAll()
                        // Allow static resources
                        .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                        // All other routes require authentication
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService))
                        .successHandler(oAuth2LoginSuccessHandler));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Note: CustomUserDetailsService is a @Service bean and PasswordEncoder is
    // defined above.
    // AuthenticationManager produced from AuthenticationConfiguration will pick up
    // the UserDetailsService.

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

}

package com.thisjowi.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.util.Map;
import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.thisjowi.auth.entity.Deployment;
import com.thisjowi.auth.entity.Account;
import com.thisjowi.auth.repository.UserRepository;
import com.thisjowi.auth.service.UserService;
import com.thisjowi.auth.service.ChangePasswordService;
import com.thisjowi.auth.service.EmailService;
import com.thisjowi.auth.service.OrganizationService;
import com.thisjowi.auth.service.LdapAuthenticationService;
import com.thisjowi.auth.utils.JwtUtil;
import com.thisjowi.auth.dto.ChangePasswordRequest;
import com.thisjowi.auth.dto.OrganizationRequest;
import com.thisjowi.auth.dto.OrganizationResponse;
import com.thisjowi.auth.dto.LdapLoginRequest;

import org.springframework.security.crypto.password.PasswordEncoder;
import com.thisjowi.auth.entity.User;
import java.util.Random;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.TimeUnit;

import com.thisjowi.auth.entity.Organization;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthRestController {
    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final ChangePasswordService changePasswordService;
    private final EmailService emailService;
    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate;
    private final OrganizationService organizationService;
    private final LdapAuthenticationService ldapAuthenticationService;

    @Value("${spring.security.oauth2.client.registration.github.client-id}")
    private String githubClientId;

    @Value("${spring.security.oauth2.client.registration.github.client-secret}")
    private String githubClientSecret;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String googleClientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String googleClientSecret;

    private final Logger log = LoggerFactory.getLogger(AuthRestController.class);

    public AuthRestController(AuthenticationManager authenticationManager,
            UserRepository userRepository, PasswordEncoder passwordEncoder,
            UserService userService, JwtUtil jwtUtil,
            ChangePasswordService changePasswordService,
            EmailService emailService,
            StringRedisTemplate redisTemplate,
            RestTemplate restTemplate,
            OrganizationService organizationService,
            LdapAuthenticationService ldapAuthenticationService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.changePasswordService = changePasswordService;
        this.emailService = emailService;
        this.redisTemplate = redisTemplate;
        this.restTemplate = restTemplate;
        this.organizationService = organizationService;
        this.ldapAuthenticationService = ldapAuthenticationService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> apiLogin(@RequestBody Map<String, String> body, HttpServletRequest request) {
        log.info("Login attempt received from: {}", request.getRemoteAddr());

        // Use email as primary identifier
        String identifierRaw = body.get("email");
        final String identifier = (identifierRaw != null) ? identifierRaw.trim() : null;
        String passwordRaw = body.get("password");
        final String password = (passwordRaw != null) ? passwordRaw.trim() : null;

        if (identifier == null || identifier.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Missing or empty email or password"));
        }

        try {
            var token = new UsernamePasswordAuthenticationToken(identifier, password);
            var auth = authenticationManager.authenticate(token);
            SecurityContextHolder.getContext().setAuthentication(auth);
            // Create session if necessary
            request.getSession(true);

            // Get user details including ID
            var user = userRepository.findByEmail(identifier)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Update last login
            user.setLastLogin(LocalDate.now());
            userRepository.save(user);

            // generate JWT token with user ID
            String jwtToken = jwtUtil.generateToken(user.getId(), user.getEmail());
            log.info("User '{}' (ID: {}) authenticated successfully", user.getEmail(), user.getId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "email", user.getEmail(),
                    "token", jwtToken,
                    "userId", user.getId(),
                    "accountType", user.getAccountType() != null ? user.getAccountType().toString() : "Community"));
        } catch (AuthenticationException ex) {
            log.warn("Authentication failed for identifier {}: {}", identifier, ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "Invalid credentials"));
        }
    }

    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@RequestBody Map<String, String> body) {
        // Support both 'token' (legacy/implicit) and 'code' (auth code flow)
        String code = body.get("code");
        String idTokenParam = body.get("token");

        try {
            String email = null;

            if (code != null) {
                // Exchange Auth Code for Token (Backend Flow)
                String tokenUrl = "https://oauth2.googleapis.com/token";
                Map<String, String> tokenParams = new java.util.HashMap<>();
                tokenParams.put("client_id", googleClientId);
                tokenParams.put("client_secret", googleClientSecret);
                tokenParams.put("code", code);
                tokenParams.put("grant_type", "authorization_code");
                // For Google Sign-In (native), redirect_uri is often empty or not required,
                // but if obtained via web flow it must match.
                // If obtained via google_sign_in serverAuthCode, it usually doesn't need
                // redirect_uri or needs ""
                tokenParams.put("redirect_uri", body.getOrDefault("redirect_uri", ""));

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);

                // Convert Map to MultiValueMap for Form URL Encoded
                org.springframework.util.MultiValueMap<String, String> map = new org.springframework.util.LinkedMultiValueMap<>();
                for (Map.Entry<String, String> entry : tokenParams.entrySet()) {
                    map.add(entry.getKey(), entry.getValue());
                }

                HttpEntity<org.springframework.util.MultiValueMap<String, String>> request = new HttpEntity<>(map,
                        headers);

                ResponseEntity<Map> tokenResponse = restTemplate.postForEntity(tokenUrl, request, Map.class);
                Map responseBody = tokenResponse.getBody();

                if (responseBody == null || responseBody.get("access_token") == null) {
                    log.error("Google token exchange failed: {}", responseBody);
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message", "Failed to exchange Google code"));
                }

                // Get User Info
                String accessToken = (String) responseBody.get("access_token");
                String userInfoUrl = "https://www.googleapis.com/oauth2/v3/userinfo";
                HttpHeaders authHeaders = new HttpHeaders();
                authHeaders.setBearerAuth(accessToken);
                HttpEntity<Void> userRequest = new HttpEntity<>(authHeaders);

                ResponseEntity<Map> userResponse = restTemplate.exchange(userInfoUrl, HttpMethod.GET, userRequest,
                        Map.class);
                Map<String, Object> userData = userResponse.getBody();
                email = (String) userData.get("email");

            } else if (idTokenParam != null) {
                // Existing ID Token logic
                String[] parts = idTokenParam.split("\\.");
                if (parts.length < 2) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message", "Invalid token format"));
                }
                String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                if (payload.contains("\"email\":\"")) {
                    int start = payload.indexOf("\"email\":\"") + 9;
                    int end = payload.indexOf("\"", start);
                    email = payload.substring(start, end);
                }
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Token or Code is required"));
            }

            if (email == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email not found"));
            }

            // Find or create user
            String finalEmail = email.trim();
            log.info("Google Login: Searching for user with email: '{}'", finalEmail);
            User user = userRepository.findByEmail(finalEmail).orElseGet(() -> {
                log.info("Google Login: User not found with email: '{}'. Creating new user.", finalEmail);
                User newUser = new User();
                newUser.setEmail(finalEmail);
                newUser.setPassword(passwordEncoder.encode("GOOGLE_OAUTH_" + java.util.UUID.randomUUID()));
                newUser.setCreatedAt(LocalDateTime.now());
                return userService.saveUser(newUser);
            });

            // Update last login
            user.setLastLogin(LocalDate.now());
            User savedUser = userService.saveUser(user);
            log.info("User saved with ID: {}", savedUser.getId());

            // Generate JWT
            String jwtToken = jwtUtil.generateToken(savedUser.getId(), savedUser.getEmail());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "email", user.getEmail(),
                    "token", jwtToken,
                    "userId", savedUser.getId(),
                    "accountType", user.getAccountType() != null ? user.getAccountType().toString() : "Community"));

        } catch (Exception e) {
            log.error("Google login error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Google login failed"));
        }
    }

    @PostMapping("/github")
    public ResponseEntity<?> githubLogin(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        if (code == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Code is required"));
        }

        try {
            // 1. Exchange code for access token
            String tokenUrl = "https://github.com/login/oauth/access_token";
            Map<String, String> tokenParams = new java.util.HashMap<>();
            tokenParams.put("client_id", githubClientId);
            tokenParams.put("client_secret", githubClientSecret);
            tokenParams.put("code", code);
            tokenParams.put("redirect_uri", body.getOrDefault("redirect_uri", "thisjowi://callback"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.Collections.singletonList(org.springframework.http.MediaType.APPLICATION_JSON));
            HttpEntity<Map<String, String>> request = new HttpEntity<>(tokenParams, headers);

            ResponseEntity<Map> tokenResponse = restTemplate.postForEntity(tokenUrl, request, Map.class);
            Map responseBody = tokenResponse.getBody();
            String accessToken = (String) responseBody.get("access_token");

            if (accessToken == null) {
                log.error("GitHub token exchange failed. Response: {}", responseBody);
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Failed to get access token from GitHub"));
            }

            // 2. Get User Info
            String userUrl = "https://api.github.com/user";
            HttpHeaders authHeaders = new HttpHeaders();
            authHeaders.setBearerAuth(accessToken);
            HttpEntity<Void> userRequest = new HttpEntity<>(authHeaders);

            ResponseEntity<Map> userResponse = restTemplate.exchange(userUrl, HttpMethod.GET, userRequest, Map.class);
            Map<String, Object> userData = userResponse.getBody();

            String email = (String) userData.get("email");

            // If email is private, we need to fetch it separately
            if (email == null) {
                String emailsUrl = "https://api.github.com/user/emails";
                ResponseEntity<List<Map>> emailsResponse = restTemplate.exchange(emailsUrl, HttpMethod.GET, userRequest,
                        new org.springframework.core.ParameterizedTypeReference<List<Map>>() {
                        });
                List<Map> emails = emailsResponse.getBody();
                if (emails != null) {
                    for (Map emailObj : emails) {
                        if (Boolean.TRUE.equals(emailObj.get("primary"))
                                && Boolean.TRUE.equals(emailObj.get("verified"))) {
                            email = (String) emailObj.get("email");
                            break;
                        }
                    }
                }
            }

            if (email == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Could not retrieve email from GitHub"));
            }

            // 3. Login/Register logic
            String finalEmail = email.trim();
            log.info("GitHub Login: Searching for user with email: '{}'", finalEmail);
            User user = userRepository.findByEmail(finalEmail).orElseGet(() -> {
                log.info("GitHub Login: User not found with email: '{}'. Creating new user.", finalEmail);
                User newUser = new User();
                newUser.setEmail(finalEmail);
                newUser.setPassword(passwordEncoder.encode("GITHUB_OAUTH_" + java.util.UUID.randomUUID()));
                newUser.setCreatedAt(LocalDateTime.now());
                newUser.setVerified(true);
                newUser.setAccountType(Account.Community); // Default
                newUser.setDeploymentType(Deployment.Cloud); // Default
                return userService.saveUser(newUser);
            });

            user.setLastLogin(LocalDate.now());
            User savedUser = userService.saveUser(user);
            log.info("GitHub User saved with ID: {}", savedUser.getId());

            String jwtToken = jwtUtil.generateToken(savedUser.getId(), savedUser.getEmail());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "email", user.getEmail(),
                    "token", jwtToken,
                    "userId", savedUser.getId(),
                    "accountType", user.getAccountType() != null ? user.getAccountType().toString() : "Community"));

        } catch (Exception e) {
            log.error("GitHub login error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "GitHub login failed"));
        }
    }

    @PostMapping("/initiate-register")
    public ResponseEntity<?> apiInitiateRegister(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email is required"));
        }

        if (userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "message", "Email already exists"));
        }

        String verificationCode = generateVerificationCode();
        // Store in Redis with 10 minutes TTL
        redisTemplate.opsForValue().set("REG_OTP:" + email, verificationCode, 10, TimeUnit.MINUTES);

        try {
            emailService.sendVerificationEmail(email, verificationCode);
            return ResponseEntity.ok(Map.of("success", true, "message", "Verification code sent"));
        } catch (Exception e) {
            log.error("Failed to send email", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Failed to send verification email"));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> apiRegister(@RequestBody Map<String, Object> body) {
        String email = (String) body.get("email");
        String password = (String) body.get("password");
        String otp = (String) body.get("otp");

        if (otp == null || otp.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "OTP is required"));
        }

        String storedOtp = redisTemplate.opsForValue().get("REG_OTP:" + email);
        if (storedOtp == null || !storedOtp.equals(otp)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid or expired OTP"));
        }

        String fullName = (String) body.get("fullName");
        String country = (String) body.get("country");
        String birthdateStr = (String) body.get("birthdate");
        String accountTypeStr = (String) body.get("accountType");
        String deploymentTypeStr = (String) body.get("hostingMode");
        Map<String, Object> ldapConfig = (Map<String, Object>) body.get("ldapConfig");

        if (password == null || email == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Missing email or password"));
        }

        if (userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "message", "Email already exists"));
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));

        if (fullName != null && !fullName.trim().isEmpty()) {
            user.setFullName(fullName.trim());
        } else {
            user.setFullName(email.split("@")[0]);
        }

        if (country != null && !country.trim().isEmpty()) {
            user.setCountry(country.trim());
        }

        if (birthdateStr != null && !birthdateStr.trim().isEmpty()) {
            try {
                user.setBirthdate(LocalDate.parse(birthdateStr.trim()));
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("success", false, "message", "Invalid birthdate format. Use yyyy-MM-dd"));
            }
        }

        if (accountTypeStr != null && !accountTypeStr.trim().isEmpty()) {
            try {
                for (Account acc : Account.values()) {
                    if (acc.name().equalsIgnoreCase(accountTypeStr.trim())) {
                        user.setAccountType(acc);
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Invalid account type: {}", accountTypeStr);
            }
        }
        if (user.getAccountType() == null) {
            user.setAccountType(Account.Community); // Default
        }

        if (deploymentTypeStr != null && !deploymentTypeStr.trim().isEmpty()) {
            try {
                String normalized = deploymentTypeStr.trim().replace("-", "");
                for (Deployment dep : Deployment.values()) {
                    if (dep.name().equalsIgnoreCase(normalized)) {
                        user.setDeploymentType(dep);
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Invalid deployment type: {}", deploymentTypeStr);
            }
        }
        if (user.getDeploymentType() == null) {
            user.setDeploymentType(Deployment.Cloud); // Default
        }

        // Organization / LDAP Logic
        if (Account.Business.equals(user.getAccountType()) && ldapConfig != null) {
            try {
                String domain = email.substring(email.indexOf("@") + 1);
                String orgName = domain.split("\\.")[0]; // Simple name from domain

                String ldapUrl = (String) ldapConfig.get("ldapUrl");
                String ldapBaseDn = (String) ldapConfig.get("ldapBaseDn");
                String ldapBindDn = (String) ldapConfig.get("ldapBindDn");
                String ldapBindPassword = (String) ldapConfig.get("ldapBindPassword");

                // Encrypt LDAP Bind Password if present
                if (ldapBindPassword != null && !ldapBindPassword.isEmpty()) {
                    ldapBindPassword = passwordEncoder.encode(ldapBindPassword);
                }

                String userSearchFilter = (String) ldapConfig.get("userSearchFilter");
                String emailAttribute = (String) ldapConfig.get("emailAttribute");
                String fullNameAttribute = (String) ldapConfig.get("fullNameAttribute");

                Organization org = organizationService.createOrganization(
                        domain,
                        orgName,
                        "Organization for " + domain,
                        ldapUrl,
                        ldapBaseDn,
                        ldapBindDn,
                        ldapBindPassword,
                        userSearchFilter,
                        emailAttribute,
                        fullNameAttribute);
                user.setOrgId(org.getId());
                log.info("Created organization '{}' for user '{}'", orgName, email);
            } catch (Exception e) {
                log.error("Failed to create organization for user {}", email, e);
            }
        }

        user.setVerified(true);
        user.setLastLogin(LocalDate.now());
        user = userService.saveUser(user);

        redisTemplate.delete("REG_OTP:" + email);

        String jwtToken = jwtUtil.generateToken(user.getId(), user.getEmail());
        log.info("Registered new user '{}' (ID: {})", user.getEmail(), user.getId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                        "success", true,
                        "email", user.getEmail(),
                        "token", jwtToken,
                        "userId", user.getId(),
                        "accountType", user.getAccountType() != null ? user.getAccountType().toString() : "Community",
                        "orgId", user.getOrgId() != null ? user.getOrgId().toString() : ""));
    }

    private String generateVerificationCode() {
        Random random = new Random();
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }

    @PostMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String code = body.get("code");

        if (email == null || code == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email and code are required"));
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isVerified()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "User already verified"));
        }

        if (code.equals(user.getVerificationCode())) {
            user.setVerified(true);
            user.setVerificationCode(null); // Clear code after successful verification
            userRepository.save(user);
            return ResponseEntity.ok(Map.of("success", true, "message", "Email verified successfully"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid verification code"));
        }
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@RequestBody Map<String, String> body) {
        String email = body.get("email");

        if (email == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email is required"));
        }

        if (userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "User already registered"));
        }

        String verificationCode = generateVerificationCode();
        // Store in Redis with 10 minutes TTL
        redisTemplate.opsForValue().set("REG_OTP:" + email, verificationCode, 10, TimeUnit.MINUTES);

        try {
            emailService.sendVerificationEmail(email, verificationCode);
            return ResponseEntity.ok(Map.of("success", true, "message", "Verification email sent"));
        } catch (Exception e) {
            log.error("Failed to send verification email to {}", email, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Failed to send email"));
        }
    }

    @GetMapping("/user")
    @ResponseBody
    public ResponseEntity<?> getUserFromToken(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Missing or invalid Authorization header"));
        }

        String token = authHeader.substring(7);
        Long userId = jwtUtil.extractUserId(token);
        if (userId == null || userId == -1L) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Invalid or expired token"));
        }

        // Return the userId directly from the token (no need to query database)
        return ResponseEntity.ok(Map.of("userId", userId));
    }

    @PutMapping("/user")
    public ResponseEntity<?> updateUser(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Missing or invalid Authorization header"));
        }

        String token = authHeader.substring(7);
        Long userId = jwtUtil.extractUserId(token);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "Invalid token"));
        }

        try {
            User user = userService.getUserById(userId);

            String country = body.get("country");
            String birthdateStr = body.get("birthdate");
            String accountTypeStr = body.get("accountType");
            String deploymentTypeStr = body.get("hostingMode");

            if (country != null) {
                user.setCountry(country.trim());
            }

            if (birthdateStr != null && !birthdateStr.trim().isEmpty()) {
                try {
                    user.setBirthdate(LocalDate.parse(birthdateStr.trim()));
                } catch (Exception e) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("success", false, "message", "Invalid birthdate format. Use yyyy-MM-dd"));
                }
            }

            if (accountTypeStr != null && !accountTypeStr.trim().isEmpty()) {
                try {
                    for (Account acc : Account.values()) {
                        if (acc.name().equalsIgnoreCase(accountTypeStr.trim())) {
                            user.setAccountType(acc);
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Invalid account type: {}", accountTypeStr);
                }
            }

            if (deploymentTypeStr != null && !deploymentTypeStr.trim().isEmpty()) {
                try {
                    String normalized = deploymentTypeStr.trim().replace("-", "");
                    for (Deployment dep : Deployment.values()) {
                        if (dep.name().equalsIgnoreCase(normalized)) {
                            user.setDeploymentType(dep);
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Invalid deployment type: {}", deploymentTypeStr);
                }
            }

            userService.saveUser(user);
            log.info("User details updated for user ID: {}", userId);

            return ResponseEntity.ok(Map.of("success", true, "message", "User details updated successfully"));

        } catch (Exception e) {
            log.error("Error updating user details for ID {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Error updating user details"));
        }
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Missing or invalid Authorization header"));
        }

        String token = authHeader.substring(7);

        try {
            // Validate password strength
            Map<String, Object> strengthValidation = changePasswordService
                    .validatePasswordStrength(request.getNewPassword());
            if (!(boolean) strengthValidation.get("isValid")) {
                log.warn("Password strength validation failed: {}", strengthValidation.get("error"));
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of(
                                "success", false,
                                "message", (String) strengthValidation.get("error")));
            }

            // Change password using the service
            Map<String, Object> result = changePasswordService.changePassword(token, request);

            if ((boolean) result.get("success")) {
                log.info("Password changed successfully for authenticated user");
                return ResponseEntity.ok(result);
            } else {
                log.warn("Password change failed: {}", result.get("message"));
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
            }

        } catch (IllegalArgumentException e) {
            log.warn("Invalid token during password change: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "Invalid token"));
        } catch (Exception e) {
            log.error("Unexpected error during password change: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "An error occurred while changing password"));
        }
    }

    @PostMapping("/validate-password-strength")
    public ResponseEntity<?> validatePasswordStrength(@RequestBody Map<String, String> body) {
        String password = body.get("password");

        if (password == null || password.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Password cannot be empty"));
        }

        try {
            Map<String, Object> result = changePasswordService.validatePasswordStrength(password);

            if ((boolean) result.get("isValid")) {
                log.debug("Password strength validation passed");
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Password is strong and meets all requirements"));
            } else {
                log.debug("Password strength validation failed: {}", result.get("error"));
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "message", (String) result.get("error")));
            }
        } catch (Exception e) {
            log.error("Error validating password strength: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Error validating password"));
        }
    }

    @DeleteMapping("/delete-account")
    public ResponseEntity<?> deleteAccount(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Missing or invalid Authorization header"));
        }

        String token = authHeader.substring(7);
        Long userId = jwtUtil.extractUserId(token);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "Invalid token"));
        }

        try {
            userService.deleteUserById(userId);
            log.info("Account deleted successfully for user ID: {}", userId);
            return ResponseEntity.ok(Map.of("success", true, "message", "Account deleted successfully"));
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
            log.error("Error deleting account for user ID {}: {}", userId, errorMessage, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Error deleting account: " + errorMessage));
        }
    }

    @GetMapping("/country/{userId}")
    public ResponseEntity<?> getUserCountry(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + userId));
        return ResponseEntity.ok(Map.of("country", user.getCountry()));
    }

    @PostMapping("/country/{userId}")
    public ResponseEntity<?> setUserCountry(@PathVariable Long userId, @RequestBody Map<String, String> body) {
        String country = body.get("country");
        if (country == null || country.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Country cannot be empty"));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + userId));
        user.setCountry(country);
        userService.saveUser(user);
        log.info("Set country '{}' for user ID {}", country, userId);
        return ResponseEntity.ok(Map.of("success", true, "country", country));
    }

    @GetMapping("/user-details/{userId}")
    public ResponseEntity<?> getUserDetails(@PathVariable Long userId) {
        try {
            User user = userService.getUserById(userId);
            return ResponseEntity.ok(user);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/user-by-email/{email}")
    public ResponseEntity<?> getUserByEmail(@PathVariable String email) {
        try {
            User user = userService.getUserByEmail(email);
            return ResponseEntity.ok(user);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/birthdate/{userId}")
    public ResponseEntity<?> getUserBirthdate(@PathVariable Long userId) {
        try {
            LocalDate birthdate = userService.getUserBirthdate(userId);
            return ResponseEntity.ok(Map.of("birthdate", birthdate));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/birthdate/{userId}")
    public ResponseEntity<?> setUserBirthdate(@PathVariable Long userId, @RequestBody Map<String, String> body) {
        String birthdateStr = body.get("birthdate");
        if (birthdateStr == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Birthdate is required"));
        }
        try {
            LocalDate birthdate = LocalDate.parse(birthdateStr);
            userService.setUserBirthdate(userId, birthdate);
            return ResponseEntity.ok(Map.of("success", true, "birthdate", birthdate));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Invalid date format or user not found"));
        }
    }

    @GetMapping("/deployment-type/{userId}")
    public ResponseEntity<?> getUserDeployment(@PathVariable Long userId) {
        try {
            Deployment deployment = userService.getDeploymentType(userId);
            return ResponseEntity.ok(Map.of("deployment", deployment));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/deployment-type/{userId}")
    public ResponseEntity<?> setUserDeployment(@PathVariable Long userId, @RequestBody Map<String, String> body) {
        String deploymentStr = body.get("deployment");
        if (deploymentStr == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Deployment type is required"));
        }
        try {
            Deployment deployment = Deployment.valueOf(deploymentStr);
            userService.setDeploymentType(userId, deployment);
            return ResponseEntity.ok(Map.of("success", true, "deployment", deployment));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Invalid deployment type"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/account-type/{userId}")
    public ResponseEntity<?> getUserAccountType(@PathVariable Long userId) {
        try {
            Account accountType = userService.getAccountType(userId);
            return ResponseEntity.ok(Map.of("accountType", accountType));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/account-type/{userId}")
    public ResponseEntity<?> setUserAccountType(@PathVariable Long userId, @RequestBody Map<String, String> body) {
        String accountTypeStr = body.get("accountType");
        if (accountTypeStr == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Account type is required"));
        }
        try {
            Account accountType = Account.valueOf(accountTypeStr);
            userService.setAccountType(userId, accountType);
            return ResponseEntity.ok(Map.of("success", true, "accountType", accountType));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Invalid account type"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/createdat/{userId}")
    public ResponseEntity<?> getUserCreatedAt(@PathVariable Long userId) {
        try {
            LocalDateTime createdAt = userService.getAccountCreationDate(userId);
            return ResponseEntity.ok(Map.of("createdAt", createdAt));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email is required"));
        }

        try {
            User user;
            try {
                user = userService.getUserByEmail(email);

                // Prevent password reset for LDAP users
                if (user.getLdapUsername() != null && !user.getLdapUsername().isEmpty()) {
                    log.warn("Blocked forgot password attempt for LDAP user: {}", email);
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("success", false, "message",
                                    "Password reset is not allowed for LDAP users. Please contact your organization administrator."));
                }
            } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
                // User not found, return success to prevent enumeration
                return ResponseEntity
                        .ok(Map.of("success", true, "message", "If an account exists, an OTP has been sent"));
            }

            String otp = String.format("%06d", new Random().nextInt(999999));

            // Store OTP in Redis with 15 minutes expiration
            redisTemplate.opsForValue().set("RESET_OTP_" + email, otp, 15, TimeUnit.MINUTES);

            String name = user.getFullName();
            if (name == null || name.isEmpty()) {
                name = "User";
            }

            emailService.sendPasswordResetEmail(email, name, otp);

            return ResponseEntity.ok(Map.of("success", true, "message", "OTP sent successfully"));
        } catch (Exception e) {
            log.error("Error in forgot password flow", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "An error occurred"));
        }
    }

    @PostMapping("/verify-reset-otp")
    public ResponseEntity<?> verifyResetOtp(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String otp = body.get("otp");

        if (email == null || otp == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email and OTP are required"));
        }

        String cachedOtp = redisTemplate.opsForValue().get("RESET_OTP_" + email);
        if (cachedOtp != null && cachedOtp.equals(otp)) {
            return ResponseEntity.ok(Map.of("success", true, "message", "OTP verified"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid or expired OTP"));
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String otp = body.get("otp");
        String newPassword = body.get("newPassword");

        if (email == null || otp == null || newPassword == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Email, OTP and New Password are required"));
        }

        // Verify OTP again to be secure
        String cachedOtp = redisTemplate.opsForValue().get("RESET_OTP_" + email);
        if (cachedOtp == null || !cachedOtp.equals(otp)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid or expired OTP"));
        }

        try {
            User user = userService.getUserByEmail(email);

            // Prevent password reset for LDAP users
            if (user.getLdapUsername() != null && !user.getLdapUsername().isEmpty()) {
                log.warn("Blocked reset password attempt for LDAP user: {}", email);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "message", "Password reset is not allowed for LDAP users."));
            }

            user.setPassword(passwordEncoder.encode(newPassword));
            userService.saveUser(user);

            // Invalidate OTP
            redisTemplate.delete("RESET_OTP_" + email);

            return ResponseEntity.ok(Map.of("success", true, "message", "Password reset successfully"));
        } catch (Exception e) {
            log.error("Error resetting password", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "An error occurred"));
        }
    }

    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Missing or invalid Authorization header"));
        }

        String token = authHeader.substring(7);
        Long currentUserId = jwtUtil.extractUserId(token);
        if (currentUserId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "Invalid token"));
        }

        try {
            List<User> users = userRepository.findAll();
            List<Map<String, Object>> userList = new java.util.ArrayList<>();

            for (User user : users) {
                // Exclude current user from the list
                if (!user.getId().equals(currentUserId)) {
                    userList.add(Map.of(
                            "id", user.getId(),
                            "email", user.getEmail(),
                            "fullName", user.getFullName() != null ? user.getFullName() : "Unknown",
                            "isOnline", true, // Default, could be enhanced with last login time
                            "avatar", null));
                }
            }

            log.info("Retrieved {} users (excluding current user)", userList.size());
            return ResponseEntity.ok(userList);
        } catch (Exception e) {
            log.error("Error fetching users: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Error fetching users"));
        }
    }

    @PutMapping("/user/public-key")
    public ResponseEntity<?> updatePublicKey(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Missing or invalid Authorization header"));
        }

        String token = authHeader.substring(7);
        Long userId = jwtUtil.extractUserId(token);
        if (userId == null) {
            log.warn("Failed to extract user ID from token during key update");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "Invalid token"));
        }

        String publicKey = body.get("publicKey");
        if (publicKey == null || publicKey.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "publicKey is required"));
        }

        try {
            log.info("Attempting to update public key for user ID: {}", userId);
            User user = userService.getUserById(userId);
            user.setPublicKey(publicKey.trim());
            userService.saveUser(user);
            log.info("✅ Public key updated successfully for user ID: {}", userId);
            return ResponseEntity.ok(Map.of("success", true, "message", "Public key updated successfully"));
        } catch (Exception e) {
            log.error("❌ Error updating public key for user ID {}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Error updating public key"));
        }
    }

    @GetMapping("/user/{userId}/public-key")
    public ResponseEntity<?> getPublicKey(@PathVariable Long userId) {
        log.info("🔍 Request to fetch public key for user ID: {}", userId);
        try {
            User user = userService.getUserById(userId);
            if (user.getPublicKey() == null || user.getPublicKey().isEmpty()) {
                log.warn("⚠️ Public key not found in record for user ID: {}", userId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "Public key not found for this user"));
            }
            log.info("✅ Public key found for user ID: {}", userId);
            return ResponseEntity.ok(Map.of("success", true, "publicKey", user.getPublicKey()));
        } catch (Exception e) {
            log.error("❌ User not found while fetching public key for ID: {}", userId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", "User not found"));
        }
    }

    @GetMapping("/users/search")
    public ResponseEntity<?> searchUsers(
            @RequestParam String q,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Missing or invalid Authorization header"));
        }

        String token = authHeader.substring(7);
        Long currentUserId = jwtUtil.extractUserId(token);
        if (currentUserId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "Invalid token"));
        }

        try {
            if (q == null || q.trim().isEmpty()) {
                return ResponseEntity.ok(java.util.Collections.emptyList());
            }

            String searchQuery = "%" + q.toLowerCase() + "%";
            List<User> users = userRepository.findAll();
            List<Map<String, Object>> results = new java.util.ArrayList<>();

            for (User user : users) {
                // Exclude current user
                if (!user.getId().equals(currentUserId)) {
                    // Search in email and fullName (case-insensitive)
                    if ((user.getEmail() != null && user.getEmail().toLowerCase().contains(q.toLowerCase())) ||
                            (user.getFullName() != null
                                    && user.getFullName().toLowerCase().contains(q.toLowerCase()))) {
                        results.add(Map.of(
                                "id", user.getId(),
                                "email", user.getEmail(),
                                "fullName", user.getFullName() != null ? user.getFullName() : "Unknown",
                                "isOnline", true,
                                "avatar", null));
                    }
                }
            }

            log.info("Search query '{}' returned {} results", q, results.size());
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Error searching users: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Error searching users"));
        }
    }

    // ==================== LDAP and Organization Endpoints ====================

    /**
     * LDAP Login endpoint
     * POST /api/v1/auth/ldap/login
     * Body: { "domain": "example.com", "username": "jdoe", "password": "password" }
     */
    @PostMapping("/ldap/login")
    public ResponseEntity<?> ldapLogin(@Valid @RequestBody LdapLoginRequest request, HttpServletRequest httpRequest) {
        log.info("LDAP login attempt for domain: {} username: {}", request.getDomain(), request.getUsername());

        try {
            // Authenticate with LDAP
            User user = ldapAuthenticationService.authenticateWithLdap(
                    request.getDomain(),
                    request.getUsername(),
                    request.getPassword());

            // Update last login
            user.setLastLogin(LocalDate.now());
            userRepository.save(user);

            // Generate JWT token
            String jwtToken = jwtUtil.generateToken(user.getId(), user.getEmail());
            log.info("LDAP user '{}' (ID: {}) authenticated successfully", user.getEmail(), user.getId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "userId", user.getId(),
                    "orgId", user.getOrgId(),
                    "email", user.getEmail(),
                    "ldapUsername", user.getLdapUsername(),
                    "token", jwtToken));

        } catch (IllegalArgumentException e) {
            log.warn("LDAP login failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("LDAP authentication error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "LDAP authentication failed"));
        }
    }

    /**
     * Create Organization endpoint
     * POST /api/v1/auth/organizations
     */
    @PostMapping("/organizations")
    public ResponseEntity<?> createOrganization(@Valid @RequestBody OrganizationRequest request) {
        log.info("Creating organization with domain: {}", request.getDomain());

        try {
            var organization = organizationService.createOrganization(
                    request.getDomain(),
                    request.getName(),
                    request.getDescription(),
                    request.getLdapUrl(),
                    request.getLdapBaseDn(),
                    request.getLdapBindDn(),
                    request.getLdapBindPassword(),
                    request.getUserSearchFilter(),
                    request.getEmailAttribute(),
                    request.getFullNameAttribute());

            var response = new OrganizationResponse(
                    organization.getId(),
                    organization.getDomain(),
                    organization.getName(),
                    organization.getDescription(),
                    organization.getLdapUrl(),
                    organization.getLdapBaseDn(),
                    organization.getUserSearchFilter(),
                    organization.getEmailAttribute(),
                    organization.getFullNameAttribute(),
                    organization.isLdapEnabled(),
                    organization.isActive(),
                    organization.getCreatedAt(),
                    organization.getUpdatedAt());

            log.info("Organization created successfully with ID: {}", organization.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            log.warn("Organization creation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating organization: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Failed to create organization"));
        }
    }

    /**
     * Get Organization by domain
     * GET /api/v1/auth/organizations/{domain}
     */
    @GetMapping("/organizations/{domain}")
    public ResponseEntity<?> getOrganization(@PathVariable String domain) {
        log.info("Fetching organization with domain: {}", domain);

        try {
            var organization = organizationService.getOrganizationByDomain(domain)
                    .orElseThrow(() -> new IllegalArgumentException("Organization not found"));

            var response = new OrganizationResponse(
                    organization.getId(),
                    organization.getDomain(),
                    organization.getName(),
                    organization.getDescription(),
                    organization.getLdapUrl(),
                    organization.getLdapBaseDn(),
                    organization.getUserSearchFilter(),
                    organization.getEmailAttribute(),
                    organization.getFullNameAttribute(),
                    organization.isLdapEnabled(),
                    organization.isActive(),
                    organization.getCreatedAt(),
                    organization.getUpdatedAt());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Organization not found: {}", domain);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", "Organization not found"));
        }
    }

    /**
     * Update Organization LDAP settings
     * PUT /api/v1/auth/organizations/{orgId}
     */
    @PutMapping("/organizations/{orgId}")
    public ResponseEntity<?> updateOrganization(
            @PathVariable java.util.UUID orgId,
            @RequestBody Map<String, String> request) {
        log.info("Updating organization with ID: {}", orgId);

        try {
            var organization = organizationService.updateOrganization(
                    orgId,
                    request.get("ldapUrl"),
                    request.get("ldapBaseDn"),
                    request.get("ldapBindDn"),
                    request.get("ldapBindPassword"),
                    request.get("userSearchFilter"),
                    request.get("emailAttribute"),
                    request.get("fullNameAttribute"));

            var response = new OrganizationResponse(
                    organization.getId(),
                    organization.getDomain(),
                    organization.getName(),
                    organization.getDescription(),
                    organization.getLdapUrl(),
                    organization.getLdapBaseDn(),
                    organization.getUserSearchFilter(),
                    organization.getEmailAttribute(),
                    organization.getFullNameAttribute(),
                    organization.isLdapEnabled(),
                    organization.isActive(),
                    organization.getCreatedAt(),
                    organization.getUpdatedAt());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Organization update failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating organization: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Failed to update organization"));
        }
    }

    /**
     * Test LDAP Connection
     * POST /api/v1/auth/organizations/test-connection
     */
    @PostMapping("/organizations/test-connection")
    public ResponseEntity<?> testConnection(@RequestBody Map<String, String> request) {
        String ldapUrl = request.get("ldapUrl");
        String ldapBaseDn = request.get("ldapBaseDn");
        String ldapBindDn = request.get("ldapBindDn");
        String ldapBindPassword = request.get("ldapBindPassword");

        if (ldapUrl == null || ldapBaseDn == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "URL and Base DN are required"));
        }

        try {
            // Create a temporary organization object to test
            Organization tempOrg = new Organization();
            tempOrg.setLdapUrl(ldapUrl);
            tempOrg.setLdapBaseDn(ldapBaseDn);
            tempOrg.setLdapBindDn(ldapBindDn);
            tempOrg.setLdapBindPassword(ldapBindPassword);
            tempOrg.setDomain("test.temp"); // Dummy

            boolean success = ldapAuthenticationService.testLdapConnection(tempOrg);

            return ResponseEntity.ok(Map.of("success", success, "message", "Connection successful"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Connection failed: " + e.getMessage()));
        }
    }
}
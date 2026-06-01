package com.thisjowi.auth.handler;

import com.thisjowi.auth.entity.User;
import com.thisjowi.auth.repository.UserRepository;
import com.thisjowi.auth.utils.JwtUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public OAuth2LoginSuccessHandler(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");
        
        if (email == null) {
            // Should have been caught by UserService, but just in case
            response.sendRedirect("/login?error=no_email");
            return;
        }

        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found after OAuth2 login"));
        
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        
        // Redirect to app via deep link
        // The scheme 'thisjowi' must be configured in the Flutter app (AndroidManifest.xml / Info.plist)
        String targetUrl = UriComponentsBuilder.fromUriString("thisjowi://auth/callback")
                .queryParam("token", token)
                .queryParam("email", email)
                .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}

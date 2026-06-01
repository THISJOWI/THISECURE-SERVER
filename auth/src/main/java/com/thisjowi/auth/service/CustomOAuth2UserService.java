package com.thisjowi.auth.service;

import com.thisjowi.auth.entity.Account;
import com.thisjowi.auth.entity.Deployment;
import com.thisjowi.auth.entity.User;
import com.thisjowi.auth.repository.UserRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    public CustomOAuth2UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");
        
        // GitHub might not return email if it's private. 
        // In a real scenario, we might need to make a separate API call to /user/emails if email is null.
        // For now, we assume email is available (scope: user:email).

        if (email == null) {
             // Fallback for GitHub if email is not in attributes (sometimes happens)
             // We could try to get "login" as a fallback but we need email for our system.
             throw new OAuth2AuthenticationException("Email not found from OAuth2 provider");
        }

        Optional<User> userOptional = userRepository.findByEmail(email);
        User user;
        if (userOptional.isPresent()) {
            user = userOptional.get();
            user.setLastLogin(LocalDate.now());
            userRepository.save(user);
        } else {
            user = new User();
            user.setEmail(email);
            user.setFullName(name != null ? name : email.split("@")[0]);
            user.setAccountType(Account.Community);
            user.setDeploymentType(Deployment.Cloud);
            user.setVerified(true);
            user.setLastLogin(LocalDate.now());
            // Set a random password since they login via OAuth
            user.setPassword(UUID.randomUUID().toString()); 
            
            userRepository.save(user);
        }

        return oAuth2User;
    }
}

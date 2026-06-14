package com.thisjowi.otp.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class otp {

    private Long id;

    private String userId;

    private String email;

    private String secret;

    private Long expiresAt;

    private String type;

    private String issuer;

    private Integer digits;

    private Integer period;

    private String algorithm;

    private Boolean valid;

}

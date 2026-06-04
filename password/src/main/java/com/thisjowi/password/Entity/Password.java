package com.thisjowi.password.Entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Password {

    Long id;

    String password;

    @JsonProperty("title")
    String name;

    @JsonProperty("website")
    String website;

    @JsonProperty("userId")
    String userId;

}

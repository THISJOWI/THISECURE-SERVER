package com.thisjowi.note.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Note {

   private Long Id;

   private String content;

   private String title;

   @DateTimeFormat
   private LocalDateTime createdAt;

   private Long userId;

   private Long version = 0L;

}

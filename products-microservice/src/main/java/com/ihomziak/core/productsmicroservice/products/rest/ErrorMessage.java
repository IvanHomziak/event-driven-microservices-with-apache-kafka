package com.ihomziak.core.productsmicroservice.products.rest;

import lombok.*;

import java.util.Date;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ErrorMessage {

    private Date timestamp;
    private String message;
    private String details;
}

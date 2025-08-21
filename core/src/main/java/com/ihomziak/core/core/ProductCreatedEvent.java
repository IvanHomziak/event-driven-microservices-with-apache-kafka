package com.ihomziak.core.core;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductCreatedEvent {

    private String productId;
    private String title;
    private BigDecimal price;
    private int quantity;
}

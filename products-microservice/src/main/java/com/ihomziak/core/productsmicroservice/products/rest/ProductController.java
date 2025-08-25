package com.ihomziak.core.productsmicroservice.products.rest;

import com.ihomziak.core.productsmicroservice.products.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.concurrent.ExecutionException;

@Slf4j
@RestController
@RequestMapping("/products") // http://localhost:<port>/products
public class ProductController {

    private final ProductService productService;

    @Autowired
    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/async")
    public ResponseEntity<String> createProduct(@RequestBody CreateProductRestModel product) {
        String productId = productService.createProductAsync(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(productId);
    }

    @PostMapping("/sync")
    public ResponseEntity<?> createProductSync(@RequestBody CreateProductRestModel product) {
        String productId;
        try {
            productId = productService.createProductSync(product);
        } catch (ExecutionException | InterruptedException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ErrorMessage.builder()
                            .message(e.getMessage())
                            .timestamp(new Date())
                            .details("/products/sync")
                            .build()
            );
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(productId);
    }
}

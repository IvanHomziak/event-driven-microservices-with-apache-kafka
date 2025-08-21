package com.ihomziak.core.productsmicroservice.products.service;


import com.ihomziak.core.productsmicroservice.products.rest.CreateProductRestModel;

import java.util.concurrent.ExecutionException;

public interface ProductService {

    String createProductAsync(CreateProductRestModel product);
    String createProductSync(CreateProductRestModel product) throws ExecutionException, InterruptedException;
}

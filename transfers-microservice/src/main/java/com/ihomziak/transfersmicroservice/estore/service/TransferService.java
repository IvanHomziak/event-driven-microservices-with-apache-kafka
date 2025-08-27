package com.ihomziak.transfersmicroservice.estore.service;

import com.ihomziak.transfersmicroservice.estore.model.TransferRestModel;

public interface TransferService {
    boolean transfer(TransferRestModel productPaymentRestModel);
}

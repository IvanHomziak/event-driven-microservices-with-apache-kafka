package com.ihomziak.transfersmicroservice.estore.io;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<TransferEntity, String> {

}

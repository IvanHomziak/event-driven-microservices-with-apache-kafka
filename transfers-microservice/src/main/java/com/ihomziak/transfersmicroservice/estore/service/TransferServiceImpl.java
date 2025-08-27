package com.ihomziak.transfersmicroservice.estore.service;

import com.ihomziak.core.events.DepositRequestedEvent;
import com.ihomziak.core.events.WithdrawalRequestedEvent;
import com.ihomziak.transfersmicroservice.estore.error.TransferServiceException;
import com.ihomziak.transfersmicroservice.estore.io.TransferEntity;
import com.ihomziak.transfersmicroservice.estore.io.TransferRepository;
import com.ihomziak.transfersmicroservice.estore.model.TransferRestModel;
import org.apache.kafka.common.Uuid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Service
public class TransferServiceImpl implements TransferService {

	private final Logger LOGGER = LoggerFactory.getLogger(this.getClass());

	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final Environment environment;
	private final RestTemplate restTemplate;
	private final TransferRepository transferRepository;

	public TransferServiceImpl(KafkaTemplate<String, Object> kafkaTemplate, Environment environment,
			RestTemplate restTemplate, TransferRepository transferRepository) {
		this.kafkaTemplate = kafkaTemplate;
		this.environment = environment;
		this.restTemplate = restTemplate;
		this.transferRepository = transferRepository;
	}

	@Transactional("transactionManager")
	@Override
	public boolean transfer(TransferRestModel transferRestModel) {

		WithdrawalRequestedEvent withdrawalEvent = new WithdrawalRequestedEvent(transferRestModel.getSenderId(),
				transferRestModel.getRecepientId(), transferRestModel.getAmount());
		DepositRequestedEvent depositEvent = new DepositRequestedEvent(transferRestModel.getSenderId(),
				transferRestModel.getRecepientId(), transferRestModel.getAmount());
	
		try {
			
			saveTransferDetails(transferRestModel);
			
			kafkaTemplate.send(environment.getProperty("withdraw-money-topic", "withdraw-money-topic"),
					withdrawalEvent);
			LOGGER.info("Sent event to withdrawal topic.");

			// Business logic that causes and error
			callRemoteServce();

			kafkaTemplate.send(environment.getProperty("deposit-money-topic", "deposit-money-topic"), depositEvent);
			LOGGER.info("Sent event to deposit topic");

		} catch (Exception ex) {
			LOGGER.error(ex.getMessage(), ex);
			throw new TransferServiceException(ex);
		}

		return true;
	}
	
	//@Transactional("transactionManager")
	private void saveTransferDetails(TransferRestModel transferRestModel) {
		TransferEntity transferEntity = new TransferEntity();
		BeanUtils.copyProperties(transferRestModel, transferEntity);
		transferEntity.setTransferId(Uuid.randomUuid().toString());
		
		// Save record to a database table
		transferRepository.save(transferEntity);
	}

	private ResponseEntity<String> callRemoteServce() throws Exception {
		String requestUrl = "http://localhost:8082/response/200";
		ResponseEntity<String> response = restTemplate.exchange(requestUrl, HttpMethod.GET, null, String.class);

		if (response.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
			throw new Exception("Destination Microservice not availble");
		}

		if (response.getStatusCode().value() == HttpStatus.OK.value()) {
			LOGGER.info("Received response from mock service: " + response.getBody());
		}
		return response;
	}

}

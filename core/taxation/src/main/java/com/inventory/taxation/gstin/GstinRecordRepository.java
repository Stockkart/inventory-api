package com.inventory.taxation.gstin;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface GstinRecordRepository extends MongoRepository<GstinRecord, String> {}

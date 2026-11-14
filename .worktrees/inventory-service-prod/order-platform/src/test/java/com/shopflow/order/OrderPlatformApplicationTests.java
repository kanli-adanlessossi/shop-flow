package com.shopflow.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// No broker runs in the unit-test run. Without these two settings the Kafka listener and the topic
// creation at startup keep retrying it and flood the log (tens of thousands of lines).
@SpringBootTest(properties = { "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false" })
class OrderPlatformApplicationTests {

	@Test
	void contextLoads() {
	}

}

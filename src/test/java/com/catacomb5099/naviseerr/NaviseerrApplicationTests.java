package com.catacomb5099.naviseerr;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NaviseerrApplicationTests {

	@Test
	void contextLoads() {
	}

}

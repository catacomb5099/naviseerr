package com.catacomb5099.naviseerr;

import com.catacomb5099.naviseerr.curator.CuratorScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NaviseerrApplicationTests {

	@Autowired CuratorScheduler curatorScheduler;

	@Test
	void contextLoads() {
	}

	/** The jar's default must stay off: a test JVM or an IntelliJ start reading the dev .env must never call a curator. */
	@Test
	void firstRunOnStart_isOffUnlessTheEnvironmentTurnsItOn() {
		assertFalse(curatorScheduler.isFirstRunOnStart());
	}

}

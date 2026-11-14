package com.shopflow.inventory.infra;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * Turns on {@code @Scheduled} and ShedLock. With {@code spring.threads.virtual.enabled=true} Boot
 * runs the jobs on a virtual-thread scheduler. A cron job is not started again on the same instance
 * while its previous run is still going; ShedLock extends that to all instances through the
 * {@code shedlock} table (see {@code V3__create_shedlock.sql}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulingConfig {

	/** Injected where "now" matters, so tests can fix the time. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/**
	 * Locks in the application database. {@code usingDbTime()} takes lock times from the database
	 * clock, so instances with drifting clocks cannot disagree about when a lock expires.
	 */
	@Bean
	LockProvider lockProvider(DataSource dataSource) {
		return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
				.withJdbcTemplate(new JdbcTemplate(dataSource))
				.usingDbTime()
				.build());
	}

}

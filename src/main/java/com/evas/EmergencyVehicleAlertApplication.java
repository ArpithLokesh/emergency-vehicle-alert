package com.evas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// @ConfigurationPropertiesScan finds every @ConfigurationProperties class
// under this package and registers it as a bean automatically — without
// it, RoutingProperties/OsrmCacheProperties would need an explicit
// @EnableConfigurationProperties(...) listing each one by name.
// @EnableScheduling activates @Scheduled methods (the stall watchdog) —
// like @EnableCaching, the annotation must be present or @Scheduled is inert.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class EmergencyVehicleAlertApplication {

	public static void main(String[] args) {
		SpringApplication.run(EmergencyVehicleAlertApplication.class, args);
	}

}

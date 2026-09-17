package com.evas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @ConfigurationPropertiesScan finds every @ConfigurationProperties class
// under this package and registers it as a bean automatically — without
// it, RoutingProperties/OsrmCacheProperties would need an explicit
// @EnableConfigurationProperties(...) listing each one by name.
@SpringBootApplication
@ConfigurationPropertiesScan
public class EmergencyVehicleAlertApplication {

	public static void main(String[] args) {
		SpringApplication.run(EmergencyVehicleAlertApplication.class, args);
	}

}

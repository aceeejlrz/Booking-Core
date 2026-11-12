package com.ticketdrop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TicketDropApplication {

	public static void main(String[] args) {
		SpringApplication.run(TicketDropApplication.class, args);
	}

}

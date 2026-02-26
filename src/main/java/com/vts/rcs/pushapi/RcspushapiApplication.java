//package com.vts.rcs.pushapi;
//
//import org.springframework.boot.SpringApplication;
//import org.springframework.boot.autoconfigure.SpringBootApplication;
//
//@SpringBootApplication(scanBasePackages = "com.vts.rcs")
//public class RcspushapiApplication {
//
//	public static void main(String[] args) {
//		SpringApplication.run(RcspushapiApplication.class, args);
//	}
//
//}

package com.vts.rcs.pushapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@SpringBootApplication(scanBasePackages = "com.vts.rcs")
public class RcspushapiApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(RcspushapiApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(RcspushapiApplication.class, args);
    }
}
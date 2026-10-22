package id.cermat.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AppConfig {

    /** Jam sebagai dependency, agar logika yang bergantung waktu bisa diuji tanpa menunggu. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

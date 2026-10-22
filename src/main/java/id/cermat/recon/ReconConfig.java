package id.cermat.recon;

import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import jakarta.servlet.MultipartConfigElement;

/** Batas ukuran unggahan di level server (lapis pertama). Service juga memeriksa ulang (lapis kedua). */
@Configuration
public class ReconConfig {

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(2));
        factory.setMaxRequestSize(DataSize.ofMegabytes(3));
        return factory.createMultipartConfig();
    }
}

package cn.iocoder.yudao.module.iot;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class IotServerApplication {
 public static void main(String[] args) {
  SpringApplication app = new SpringApplication(IotServerApplication.class);
  app.setAdditionalProfiles("iot"); app.run(args);
 }
}

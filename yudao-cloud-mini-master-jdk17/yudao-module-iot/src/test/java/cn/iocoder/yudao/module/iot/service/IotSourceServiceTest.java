package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.realdb.core.client.RealDbClient;
import cn.iocoder.yudao.module.iot.model.IotModels.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IotSourceServiceTest {
    @Test void disabledSourceNeverInstantiatesOrContactsClient() {
        var beans=new DefaultListableBeanFactory();
        Source source=new IotSourceService(new MockEnvironment(),beans.getBeanProvider(RealDbClient.class)).sources().get(0);
        assertThat(source.status()).isEqualTo("DISABLED");assertThat(source.enabled()).isFalse();
        assertThat(source.configured()).isFalse();assertThat(source.timeZone()).isEqualTo("UTC");
    }
    @Test void configurationStatusDoesNotClaimConnectivityOrRevealSecrets() throws Exception {
        var beans=new DefaultListableBeanFactory();RealDbClient client=mock(RealDbClient.class);
        beans.registerSingleton("realDb",client);
        var env=new MockEnvironment().withProperty("yudao.realdb.enabled","true")
                .withProperty("yudao.realdb.base-url","https://sensitive-upstream.invalid")
                .withProperty("yudao.realdb.username","sensitive-user").withProperty("yudao.realdb.password","sensitive-password")
                .withProperty("yudao.realdb.token","sensitive-token").withProperty("yudao.iot.realdb-zone","Asia/Shanghai");
        var sources=new IotSourceService(env,beans.getBeanProvider(RealDbClient.class)).sources();
        assertThat(sources).hasSize(1);assertThat(sources.get(0).status()).isEqualTo("CONFIGURED");
        assertThat(sources.get(0).timeZone()).isEqualTo("Asia/Shanghai");
        String output=new ObjectMapper().writeValueAsString(sources);
        assertThat(output).doesNotContain("sensitive-","http","password","token","username");
        verifyNoInteractions(client);
    }
    @Test void incompleteAuthenticationIsNotConfiguredAndTokenOnlyIsSupported() {
        var beans=new DefaultListableBeanFactory();RealDbClient client=mock(RealDbClient.class);beans.registerSingleton("realDb",client);
        var env=new MockEnvironment().withProperty("yudao.realdb.enabled","true").withProperty("yudao.realdb.base-url","http://127.0.0.1:1");
        IotSourceService service=new IotSourceService(env,beans.getBeanProvider(RealDbClient.class));
        assertThat(service.sources().get(0).status()).isEqualTo("NOT_CONFIGURED");
        env.withProperty("yudao.realdb.token","test-token");assertThat(service.sources().get(0).configured()).isTrue();
        env.withProperty("yudao.realdb.token","").withProperty("yudao.realdb.security-enabled","false");
        assertThat(service.sources().get(0).configured()).isTrue();verifyNoInteractions(client);
    }
}

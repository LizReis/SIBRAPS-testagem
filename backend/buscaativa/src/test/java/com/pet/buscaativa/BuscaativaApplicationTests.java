package com.pet.buscaativa;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BuscaativaApplicationTests extends SecurityTestSupport {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.pet.buscaativa.services.impl.SusApiAdapter susApi;

	@Test
	void contextLoads() {
	}

}
